package ca.jarcode.consoles.internal;

import ca.jarcode.consoles.api.nms.ConsolesNMS;
import ca.jarcode.consoles.api.nms.GeneralInternals;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableBiMap;
import ca.jarcode.consoles.Consoles;
import ca.jarcode.consoles.api.*;
import ca.jarcode.consoles.util.LocalPosition;
import ca.jarcode.consoles.api.Position2D;
import ca.jarcode.consoles.util.Region;
import org.bukkit.*;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.*;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/*

This is the bulk of how Consoles work. This class contains background
rendering, entity spawning and tracking, regions/locations, and the start
of the process for repainting.

 */
public abstract class ConsoleRenderer implements Canvas {

	private int width;
	private int height;

	// the pixel buffer drawn to, and read from when sending packet data.
	private ConsolePixelBuffer screen;

	// all the map components
	private LinkedHashMap<Position2D, ConsoleComponent> components = new LinkedHashMap<>();

	// map renderers, one per painting damage value. Sends raw packets.
	private List<ConsoleMapRenderer> renderers = new ArrayList<>();

	// logger used in this class
	private Logger logger = null;

	// entity list of item frames
	private List<ItemFrame> frames = new ArrayList<>();

	// cached background pixels
	private byte[][] bg;

	// the origin of this console (corner at lowest x and y value in world coordinates)
	protected Location pos;

	// the direction this console faces
	protected BlockFace face;

	// the starting index for the map damage values that are allocated to this console
	private short index;

	// region around the console, only including the blocks where the paintings exist.
	private Region bounds;

	// whether the console is vertical or horizontal, used in intersection calculations.
	private boolean vertical;

	// determines whether the console background is drawn or not.
	// used to improve performance on consoles that don't need backgrounds.
	private volatile boolean drawConsoleBackground = true;

	// used to track whether the current background cached has been painted before.
	// this is set to false when the background is painted, checked on each paint,
	// and set to false when the background is re-cached.
	//
	// accessed from the painting thread only.
	private HashMap<String, Boolean> bgCheck = new HashMap<>();

	// locked when data is being written to the background cache or the pixel buffer,
	// or when components are being added/removed.
	protected final Object RENDERER_LOCK = new Object();

	// lock for map id -> entity id map
	protected final Object ENTITY_MAP_LOCK = new Object();

	// Mappings for map id -> entity id, multi-threaded
	private HashBiMap<Short, Integer> entityMap = HashBiMap.create();

	// Name of the console type. Doesn't actually do anything.
	protected String type = "unknown";

	public ConsoleRenderer(int w, int h) {
		this(w, h, true);
	}
	ConsoleRenderer(int w, int h, boolean drawConsoleBackground) {
		width = w;
		height = h;
		screen = new ConsolePixelBuffer(this, width, height);
		this.drawConsoleBackground = drawConsoleBackground;

		bg = new byte[getWidth()][getHeight()];

		cacheBackground();
	}

	@SuppressWarnings("deprecation")
	void create(short index, BlockFace face, Location location) {
		this.face = face;
		pos = location.clone();
		this.index = index;
		for (int length = 0; length < width; length++) {
			for (int y = 0; y < height; y++) {
				// override the map id at the index for this map
				LocalPosition local = new LocalPosition(pos);
				int xm, zm;
				xm = 0;
				zm = 0;
				// set up bounding boxes, orientation, and NMS direction enums based on the face
				switch (face) {
					case NORTH:
						xm = (width - length) - 1;
						bounds = new Region(local, local.copy().add(width - 1, height - 1, 0));
						vertical = false;
						break;
					case SOUTH:
						xm = length;
						bounds = new Region(local, local.copy().add(width - 1, height - 1, 0));
						vertical = false;
						break;
					case EAST:
						zm = (width - length) - 1;
						bounds = new Region(local, local.copy().add(0, height - 1, width - 1));
						vertical = true;
						break;
					case WEST:
						zm = length;
						bounds = new Region(local, local.copy().add(0, height - 1, width - 1));
						vertical = true;
						break;
					default: throw new IllegalArgumentException("Direction must be north, west, east or south");
				}
				// create a map renderer and add it
				ConsoleMapRenderer renderer = new ConsoleMapRenderer(screen, length, y, this, index);
				synchronized (RENDERER_LOCK) {
					renderers.add(renderer);
				}

				GeneralInternals.InitResult result = ConsolesNMS.internals.initFrame(pos.getWorld(),
						pos.getBlockX() + xm, pos.getBlockY() + (height - y) - 1, pos.getBlockZ() + zm,
						index, face);

				// add to entity list
				frames.add(result.getEntity());
				// map the map ids to the entity ids
				entityMap.put(index, result.getEntityId());
				index++;
			}
		}
		pos = pos.getBlock().getLocation();
		screen.onCreate();
	}
	public String getType() {
		return type;
	}
	public void setType(String name) {
		this.type = name;
	}
	// I don't expect you to understand this.
	public int[] intersect(Location eye, double distance) {
		// ignore different worlds
		if (eye.getWorld() != pos.getWorld()) return null;

		double yaw = eye.getYaw() > 0 ? eye.getYaw() : 360 - Math.abs(eye.getYaw()); // remove negative degrees
		yaw += 90; // rotate +90 degrees
		if (yaw > 360)
			yaw -= 360;
		yaw  = (yaw  * Math.PI) / 180;
		double pitch  = ((eye.getPitch() + 90)  * Math.PI) / 180;

		// player-space
		double xp = Math.sin(pitch) * Math.cos(yaw);
		double zp = Math.sin(pitch) * Math.sin(yaw);
		double yp = Math.cos(pitch);

		// where our plane (screen) is in world-space, as x = c or z = c.
		// we add 1/16 to the pane, because consoles pop out by 1/16th of a block.
		double c = vertical ? pos.getX() + (face == BlockFace.WEST ? 1 - (1/16D) : (1/16D))
				: pos.getZ() + (face == BlockFace.NORTH ? 1 - (1/16D) : (1/16D));

		// now we need two points for this process, which we use from the above
		// we're supposed to use v1 - v0 here, but that's just (v0 + h) - v0, simplifying to h.
		// now, 't' from parametric equation
		double t = vertical ? ((c - eye.getX()) / xp) : ((c - eye.getZ()) / zp);
		// intersection points!
		double xi = eye.getX() + (xp * t);
		double yi = eye.getY() + (yp * t);
		double zi = eye.getZ() + (zp * t);
		// Our coordinates relative to the screen's position.
		Location intersect = new Location(eye.getWorld(), xi, yi, zi);
		Location local = intersect.clone().subtract(pos);
		// return 2D screen coordinates, relative to its top-left origin and multiply by 128.
		int y = getHeight() - (int) Math.round(local.getY() * 128);
		boolean b = (face == BlockFace.EAST || face == BlockFace.NORTH);
		int inv = b ? getWidth() : 0;
		int[] arr = vertical ?
				new int[] {b ? (inv - (int) Math.round(local.getZ() * 128)) : (int) Math.round(local.getZ() * 128), y}
				: new int[] {b ? (inv - (int) Math.round(local.getX() * 128)) : (int) Math.round(local.getX() * 128), y};
		if (arr[0] < 0 || arr[0] >= getWidth() || arr[1] < 0 || arr[1] >= getHeight())
			return null;
		// players shouldn't be able to interact in the opposite direction, or behind the console, so let's fix that.
		if (face == BlockFace.NORTH && (zp < 0 || eye.getZ() > c))
			return null;
		if (face == BlockFace.SOUTH && (zp > 0 || eye.getZ() < c))
			return null;
		if (face == BlockFace.EAST && (xp > 0 || eye.getX() < c))
			return null;
		if (face == BlockFace.WEST && (xp < 0 || eye.getX() > c))
			return null;
		// too far away!
		if (intersect.distance(eye) > distance)
			return null;
		else return arr;
	}
	// this is a way to obtain a map view by ID, regardless if it exists or not.
	// from before the NMS abstraction update
	/*
	@Deprecated
	@SuppressWarnings("deprecation")
	private MapView getView(short i, World world) {
		MapView view = Bukkit.getServer().getMap(i);
		net.minecraft.server.v1_8_R3.World mcWorld = ((CraftWorld) world).getHandle();
		if (view == null) {
			WorldMap map = Items.FILLED_MAP.getSavedMap(
					new net.minecraft.server.v1_8_R3.ItemStack(Items.MAP, 1, i), mcWorld
			);
			view = map.mapView;
		}
		return view;
	}
	*/
	public ConsoleMapRenderer[] renderers() {
		return renderers.toArray(new ConsoleMapRenderer[renderers.size()]);
	}
	public final boolean created() {
		return pos != null;
	}
	final short getMapIndex() {
		return index;
	}
	public final Region getBounds() {
		return bounds;
	}
	public final boolean protect(ItemFrame entity) {
		return frames.contains(entity);
	}
	public final boolean isFrameId(int entityId) {
		synchronized (ENTITY_MAP_LOCK) {
			for (int id : entityMap.values()) {
				if (id == entityId)
					return true;
			}
			return false;
		}
	}
	public final Logger logger() {
		return logger;
	}
	public final void setLogger(Logger logger) {
		this.logger = logger;
	}
	public final boolean log() {
		return logger != null;
	}
	public BiMap<Short, Integer> entityMap() {
		synchronized (ENTITY_MAP_LOCK) {
			return ImmutableBiMap.copyOf(entityMap);
		}
	}
	public final void drawBackground(boolean draw) {
		drawConsoleBackground = draw;
		cacheBackground();
	}
	public BlockFace getDirection() {
		return face;
	}
	public Location getLocation() {
		return pos;
	}
	public final void draw(int x, int y, byte color) {
		screen.set(x, y, color, getPaintContext());
	}
	protected String getPaintContext() {
		return ConsoleHandler.getInstance().getPainter().getPaintContext();
	}
	// The context (at the moment) is just a String with the player's name. Bukkit objects should be avoided in
	// painting methods.
	protected void paint() {
		drawComponents();
	}
	// This is an expensive method! It will repaint components to the buffer,
	// and then will trigger a repaint from all the underlying map renderers.
	// Depending on the complexity and size of the console, this could be both CPU and bandwidth intensive!
	//
	// For component programming, it is recommended to use this method as little as possible and leave it to the
	// code used to create/setup the console to handle repainting.
	//
	// It also calls threaded code, so it won't block, but will put stress on the server
	public void repaint() {
		if (Thread.currentThread().getName().equals("Console Painting Thread")) {
			throw new RuntimeException("repaint() cannot be called within a paint cycle!");
		}
		ConsoleHandler.getInstance().getPainter().repaint(this);
	}
	public void repaint(int tickDelay) {
		if (Thread.currentThread().getName().equals("Console Painting Thread")) {
			throw new RuntimeException("repaint() cannot be called within a paint cycle!");
		}
		Bukkit.getScheduler().scheduleSyncDelayedTask(Consoles.getInstance(), this::repaint, tickDelay);
	}
	public void remove() {
		synchronized (RENDERER_LOCK) {
			screen.remove();
		}
		Entity[] arr = frames.toArray(new Entity[frames.size()]);
		frames.clear();
		for (Entity entity : arr) {
			entity.remove();
		}
	}
	public List<ItemFrame> bukkitEntities() {
		return frames;
	}
	ConsolePixelBuffer getPixelBuffer() {
		return screen;
	}
	public void putComponent(Position2D position, CanvasComponent comp) {
		if (comp == null) {
			removeComponent(position);
			return;
		}
		if (comp instanceof RootComponent) {
			((RootComponent) comp).place(this);
			cacheBackground();
			return;
		}
		if (comp instanceof PreparedComponent) {
			((PreparedComponent) comp).prepare(this);
		}
		ConsoleComponent object = comp instanceof WrappedComponent ?
				(ConsoleComponent) ((WrappedComponent) comp).underlying() : (ConsoleComponent) comp;
		if (components.get(position) != null)
			removeComponent(position, false);

		components.put(position, object);
		cacheBackground();
	}
	public boolean componentAt(Position2D position) {
		synchronized (RENDERER_LOCK) {
			return components.containsKey(position);
		}
	}
	public void removeComponent(Position2D position) {
		removeComponent(position, true);
	}
	public void removeComponent(Position2D position, boolean cache) {
		synchronized (RENDERER_LOCK) {
			if (components.containsKey(position)) {
				ConsoleComponent component = components.get(position);
				component.onRemove();
				components.remove(position);
				if (cache)
					cacheBackground();
			}
		}
	}
	public void removeComponent(CanvasComponent object) {
		synchronized (RENDERER_LOCK) {
			List<Position2D> positions = components.entrySet().stream()
					.filter(entry -> entry.getValue() == object)
					.map(Map.Entry::getKey)
					.collect(Collectors.toList());
			for (Position2D position : positions) {
				ConsoleComponent component = components.get(position);
				component.onRemove();
				components.remove(position);
			}
			if (positions.size() > 0) {
				cacheBackground();
			}
		}
	}
	private void toggleBackground(String context, boolean drawn) {
		bgCheck.put(context, drawn);
	}
	private boolean drewBackground(String context) {
		return bgCheck.containsKey(context) && bgCheck.get(context);
	}
	@Deprecated
	public ConsoleComponent[] getComponents() {
		return components.values().toArray(new ConsoleComponent[components.size()]);
	}
	public ConsoleComponent getComponent(Position2D pos) {
		return components.get(pos);
	}
	private void drawComponents() {
		if (!Thread.holdsLock(RENDERER_LOCK)) synchronized (RENDERER_LOCK) {
			drawComponents();
			return;
		}
		if (!drewBackground(getPaintContext()))
			drawBackground();
		for (Position2D pos : components.keySet()) {
			ConsoleComponent obj = components.get(pos);
			if (obj.enabled()) {
				obj.paint(new ConsoleGraphics(this, obj, pos), getPaintContext());
			}
		}
	}
	void drawBackground(int x, int y, int w, int h) {
		if (!Thread.holdsLock(RENDERER_LOCK)) synchronized (RENDERER_LOCK) {
			drawBackground(x, y, w, h);
			return;
		}
		toggleBackground(getPaintContext(), true);
		for (int xi = x; xi < x + w; xi++) {
			for (int yi = y; yi < y + h; yi++) {
				draw(xi, yi, bg[xi][yi]);
			}
		}
	}
	private void drawBackground() {
		if (!Thread.holdsLock(RENDERER_LOCK)) synchronized (RENDERER_LOCK) {
			drawBackground();
			return;
		}
		for (int x = 0; x < getWidth(); x++) {
			for (int y = 0; y < getHeight(); y++) {
				draw(x, y, bg[x][y]);
			}
		}
	}
	void handleClick(int x, int y, Player player) {
		for (Map.Entry<Position2D, ConsoleComponent> entry : Collections.unmodifiableCollection(components.entrySet())) {
			Position2D pos = entry.getKey();
			ConsoleComponent comp = entry.getValue();
			if (comp.enabled() && x >= pos.getX() && y >= pos.getY()
					&& x < pos.getX() + comp.getWidth() && y < pos.getY() + comp.getHeight())
				comp.handleClick(x - pos.getX(), y - pos.getY(), player);
		}
	}
	void cacheBackground() {

		if (!Thread.holdsLock(RENDERER_LOCK)) synchronized (RENDERER_LOCK) {
			cacheBackground();
			return;
		}
		bgCheck.clear();
		if (drawConsoleBackground) cacheRootBackground();
		for (Position2D pos : components.keySet()) {
			ConsoleComponent obj = components.get(pos);
			byte b = obj.getBackground();
			if (obj.enabled() && b != -1) for (int x = pos.getX(); x < obj.getWidth() + pos.getX(); x++) {
				for (int y = pos.getY(); y < obj.getHeight() + pos.getY(); y++) {
					bg[x][y] = b;
				}
			}
		}
	}
	private void cacheRootBackground() {
		for (int x = 0; x < getWidth(); x++) {
			for (int y = 0; y < getHeight(); y++) {
				if (x <= 0 || x >= getWidth() - 1 || y <= 0 || y >= getHeight() - 1) {
					bg[x][y] = (byte) 44;
				}
				else if (x <= 1 || x >= getWidth() - 2 || y <= 1 || y >= getHeight() - 2) {
					bg[x][y] = (byte) 47;
				}
				else
					bg[x][y] = (byte) 119;
			}
		}
	}
	protected void doLater(Runnable runnable, long delay) {
		Bukkit.getScheduler().scheduleSyncDelayedTask(Consoles.getInstance(), runnable, delay);
	}
	public final int getFrameWidth() {
		return width;
	}
	public final int getFrameHeight() {
		return height;
	}
	public final int getWidth() {
		return width * ConsolePixelBuffer.SIZE;
	}
	public final int getHeight() {
		return height * ConsolePixelBuffer.SIZE;
	}
}
