package jarcode.consoles.computer;

import jarcode.consoles.ConsoleHandler;
import jarcode.consoles.Consoles;
import jarcode.consoles.ManagedConsole;
import jarcode.consoles.Position2D;
import jarcode.consoles.api.Console;
import jarcode.consoles.computer.interpreter.Lua;
import javafx.geometry.Pos;
import net.md_5.bungee.api.ChatColor;
import net.minecraft.server.v1_8_R2.NBTTagCompound;
import net.minecraft.server.v1_8_R2.NBTTagList;
import org.apache.commons.io.IOUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.CommandBlock;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.craftbukkit.v1_8_R2.CraftServer;
import org.bukkit.craftbukkit.v1_8_R2.block.CraftBlockState;
import org.bukkit.craftbukkit.v1_8_R2.command.VanillaCommandWrapper;
import org.bukkit.craftbukkit.v1_8_R2.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public class ComputerHandler implements Listener {

	private static ComputerHandler instance;

	private static final Field ITEM_STACK_HANDLE;
	private static final Constructor ITEM_STACK_CREATE;

	public static final String MINESWEEPER_PROGRAM;
	public static final String MINESWEEPER_BLOCK_PROGRAM;

	static {
		try {
			MINESWEEPER_BLOCK_PROGRAM = IOUtils.readLines(
					ComputerHandler.class.getResourceAsStream("/minesweeper/block.lua"))
					.stream().collect(Collectors.joining("\n"));
			MINESWEEPER_PROGRAM = IOUtils.readLines(
					ComputerHandler.class.getResourceAsStream("/minesweeper/minesweeper.lua"))
					.stream().collect(Collectors.joining("\n"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	static {
		Lua.map(ComputerHandler::lua_redstone, "redstone");
		Lua.map(ComputerHandler::lua_redstoneLength, "redstoneLength");
	}

	static {
		try {
			ITEM_STACK_HANDLE = CraftItemStack.class.getDeclaredField("handle");
			ITEM_STACK_HANDLE.setAccessible(true);
			ITEM_STACK_CREATE =
					CraftItemStack.class.getDeclaredConstructor(Material.class, int.class, short.class, ItemMeta.class);
			ITEM_STACK_CREATE.setAccessible(true);
		}
		catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	// we inject a vanilla command intended to be ran by command blocks
	public static void registerLinkCommand() {
		SimpleCommandMap commandMap = ((CraftServer) Bukkit.getServer()).getCommandMap();
		commandMap.register("minecraft:", new VanillaCommandWrapper(new LinkCommand()));
	}

	public static ComputerHandler getInstance() {
		return instance;
	}

	public static ItemStack newComputerStack() {
		return newComputerStack(true);
	}

	@SuppressWarnings("RedundantCast")
	private static ItemStack newComputerStack(boolean glow) {
		ItemMeta meta = Bukkit.getItemFactory().getItemMeta(Material.STAINED_GLASS);
		meta.setDisplayName(ChatColor.GREEN + "Computer");
		meta.setLore(Arrays.asList(ChatColor.RESET + "3x2", ChatColor.RESET + "Place to build"));
		ItemStack stack = null;
		try {
			stack = (ItemStack) ITEM_STACK_CREATE.newInstance(Material.STAINED_GLASS, 1, (short) 15, meta);
		} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
			e.printStackTrace();
		}
		if (glow) {
			net.minecraft.server.v1_8_R2.ItemStack nms;
			try {
				nms = (net.minecraft.server.v1_8_R2.ItemStack) ITEM_STACK_HANDLE.get((CraftItemStack) stack);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
			NBTTagCompound comp = nms.getTag();
			comp.set("ench", new NBTTagList());
			// this is why I go through the effort to set custom NBT tags
			// this prevents players from creating a computer without crafting
			// it, unless they are setting the NBT tags explicitly - which
			// would mean they are probably an admin.
			comp.setBoolean("computer", true);
			nms.setTag(comp);
		}
		return stack;
	}

	ShapedRecipe computerRecipe;
	private ArrayList<Computer> computers = new ArrayList<>();
	private HashMap<String, CommandBlock> linkRequests = new HashMap<>();
	private HashMap<Location, Computer> trackedBlocks = new LinkedHashMap<>();

	{
		instance = this;
		if (Consoles.allowCrafting) {
			computerRecipe = new ShapedRecipe(newComputerStack());
			computerRecipe.shape("AAA", "CBC", "AAA");
			computerRecipe.setIngredient('A', Material.STONE);
			computerRecipe.setIngredient('B', Material.REDSTONE_BLOCK);
			computerRecipe.setIngredient('C', Material.DIAMOND);
			Bukkit.getServer().addRecipe(computerRecipe);
		}
	}

	public ComputerHandler() {
		registerLinkCommand();
		ComputerData.init();
		Bukkit.getScheduler().scheduleSyncRepeatingTask(Consoles.getInstance(), this::saveAll, 6000, 6000);
		Bukkit.getScheduler().scheduleSyncDelayedTask(Consoles.getInstance(),
				() -> computers.addAll(ComputerData.makeAll()));
	}

	public void updateBlocks(Computer computer) {
		ManagedConsole console = computer.getConsole();
		BlockFace f = console.getDirection();
		Location at = console.getLocation();
		Location[] hm = {
				new Location(at.getWorld(), 1, 0, 0), // N
				new Location(at.getWorld(), 0, 0, 1), // E
				new Location(at.getWorld(), 1, 0, 0), // S
				new Location(at.getWorld(), 0, 0, 1), // W
		};
		Location[] pm = {
				new Location(at.getWorld(), 0, 0, 1), // N
				new Location(at.getWorld(), -1, 0, 0), // E
				new Location(at.getWorld(), 0, 0, -1), // S
				new Location(at.getWorld(), 1, 0, 0), // W
		};
		int i = -1;
		switch (f) {
			case NORTH: i = 0; break;
			case EAST: i = 1; break;
			case SOUTH: i = 2; break;
			case WEST: i = 3; break;
		}
		if (i == -1) return;
		Location p = at.clone().add(pm[i]);
		Location o;
		for (int h = 0; h < 2; h++) {
			p.setY(p.getBlockY() + h);
			o = p.clone();
			for (int t = 0; t < 3; t++) {
				if (o.getBlock().getType() == Material.REDSTONE_BLOCK && !trackedBlocks.containsKey(o)) {
					trackedBlocks.put(o.clone(), computer);
				}
				o.add(hm[i]);
			}
		}
	}

	@SuppressWarnings({"deprecation", "SynchronizationOnLocalVariableOrMethodParameter"})
	public static boolean lua_redstone(Integer index, Boolean on) {
		Computer computer = Lua.context();
		BooleanSupplier func = () -> {
			Location[] blocks = ComputerHandler.getInstance().trackedFor(computer);
			if (blocks != null && index < blocks.length && index >= 0) {
				Block block = blocks[index].getBlock();
				if (block == null) return false;
				block.setType(on ? Material.REDSTONE_BLOCK : Material.STAINED_GLASS);
				if (!on)
					block.setData((byte) 14);
				BlockState state = block.getState();
				state.update(true, true);
				return true;
			} else return false;
		};
		Object lock = new Object();
		AtomicBoolean done = new AtomicBoolean(false);
		AtomicBoolean result = new AtomicBoolean(false);
		Bukkit.getScheduler().scheduleSyncDelayedTask(Consoles.getInstance(), () -> {
			synchronized (lock) {
				result.set(func.getAsBoolean());
				done.set(true);
				lock.notify();
			}
		});
		try {
			while (!done.get()) {
				synchronized (lock) {
					lock.wait();
				}
			}
		}
		catch (InterruptedException e) {
			e.printStackTrace();
		}
		return result.get();
	}

	public static int lua_redstoneLength() {
		Computer computer = Lua.context();
		Location[] blocks = ComputerHandler.getInstance().trackedFor(computer);
		return blocks == null ? 0 : blocks.length;
	}

	public Location[] trackedFor(Computer computer) {
		return trackedBlocks.entrySet().stream()
				.filter(entry -> entry.getValue() == computer)
				.map(Map.Entry::getKey)
				.toArray(Location[]::new);
	}

	@EventHandler
	public void trackBlockPlace(BlockPlaceEvent e) {
		computers.stream()
				.filter(c -> c.getConsole().getLocation().getWorld() == e.getPlayer().getWorld()
						&& c.getConsole().getLocation().distanceSquared(e.getPlayer().getLocation()) < 64)
				.forEach(this::updateBlocks);
	}

	@EventHandler
	public void onBlockBreak(BlockBreakEvent e) {
		if (trackedBlocks.containsKey(e.getBlock().getLocation())) {
			if (e.getBlock().getType() != Material.REDSTONE_BLOCK) {
				e.setCancelled(true);
				e.getPlayer().sendMessage(ChatColor.YELLOW + "Change to redstone before removing");
			}
			else {
				trackedBlocks.remove(e.getBlock().getLocation());
			}
		}
	}
	@EventHandler
	public void onBlockPistonExtend(BlockPistonExtendEvent e) {
		if (trackedBlocks.containsKey(e.getBlock().getLocation())) {
			e.setCancelled(true);
		}
	}
	@EventHandler
	public void onBlockPistonRetract(BlockPistonRetractEvent e) {
		if (trackedBlocks.containsKey(e.getBlock().getLocation())) {
			e.setCancelled(true);
		}
	}

	public void saveAll() {
		Consoles.getInstance().getLogger().info("Saving computers...");
		long count = computers.stream().peek(Computer::save).count();
		Consoles.getInstance().getLogger().info("Saved " + count + " computers");
	}

	public void interact(Position2D pos, Player player, ManagedConsole console) {
		computers.stream().filter(comp -> comp.getConsole() == console)
				.forEach(comp -> comp.clickEvent(pos, player.getName()));
	}
	public void command(String command, Player player) {
		List<ManagedConsole> lookingAt = Arrays.asList(
				ConsoleHandler.getInstance().getConsolesLookingAt(player.getEyeLocation())
		);
		computers.stream().filter(comp -> lookingAt.contains(comp.getConsole()))
				.forEach(comp -> comp.playerCommand(command, player.getName()));
	}

	@EventHandler
	public void saveAll(PluginDisableEvent e) {
		if (e.getPlugin() == Consoles.getInstance()) {
			saveAll();
		}
	}

	@EventHandler
	public void onBlockPlace(BlockPlaceEvent e) {
		if (isComputer(e.getItemInHand())) {
			try {
				build(e.getPlayer(), e.getBlockPlaced().getLocation());
			}
			finally {
				Bukkit.getScheduler().scheduleSyncDelayedTask(Consoles.getInstance(),
						() -> e.getBlock().setType(Material.AIR));
			}
		}
	}

	@EventHandler
	public void onCraft(CraftItemEvent e) {
		if (isNamedComputer(e.getCurrentItem())) {
			e.setCurrentItem(newComputerStack());
		}
	}
	private List<Computer> getComputers() {
		return Collections.unmodifiableList(computers);
	}
	private List<Computer> getComputers(UUID uuid) {
		return computers.stream()
				.filter(computer -> computer.getOwner().equals(uuid))
				.collect(Collectors.toList());
	}
	private void build(Player player, Location location) {
		BlockFace face = direction(player);
		ManagedComputer computer = new ManagedComputer(findHostname(player), player.getUniqueId());
		computer.create(face, location);
	}
	private String findHostname(Player player) {
		String name = player.getName().toLowerCase() + "-";
		int[] index = {0};
		while (computers.stream().filter(comp -> comp.getHostname().equals(name + index[0])).findFirst().isPresent()) {
			index[0]++;
		}
		return name + index[0];
	}
	private BlockFace direction(Player player) {
		// shameless copy-paste from my other math code
		Location eye = player.getEyeLocation();
		double yaw = eye.getYaw() > 0 ? eye.getYaw() : 360 - Math.abs(eye.getYaw()); // remove negative degrees
		yaw += 90; // rotate +90 degrees
		if (yaw > 360)
			yaw -= 360;
		yaw  = (yaw  * Math.PI) / 180;
		double pitch  = ((eye.getPitch() + 90)  * Math.PI) / 180;

		double xp = Math.sin(pitch) * Math.cos(yaw);
		double zp = Math.sin(pitch) * Math.sin(yaw);
		if (Math.abs(xp) > Math.abs(zp)) {
			return xp < 0 ? BlockFace.EAST : BlockFace.WEST;
		}
		else {
			return zp < 0 ? BlockFace.SOUTH : BlockFace.NORTH;
		}
	}
	private boolean isNamedComputer(ItemStack stack) {
		ItemMeta meta = stack.getItemMeta();
		return meta.getDisplayName() != null && meta.getDisplayName().equals(ChatColor.GREEN + "Computer");
	}
	private boolean isComputer(ItemStack stack) {
		ItemMeta meta = stack.getItemMeta();

		net.minecraft.server.v1_8_R2.ItemStack nms;
		try {
			nms = (net.minecraft.server.v1_8_R2.ItemStack) ITEM_STACK_HANDLE.get(stack);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
		NBTTagCompound tag = nms.getTag();
		return tag != null && meta.getDisplayName() != null && meta.getDisplayName().equals(ChatColor.GREEN + "Computer")
				&& stack.getType() == Material.STAINED_GLASS && tag.hasKey("computer");
	}
	public boolean hostnameTaken(String hostname) {
		return computers.stream().filter(comp -> comp.getHostname().equals(hostname.toLowerCase()))
				.findFirst().isPresent();
	}
	public Computer find(String hostname) {
		return computers.stream().filter(comp -> comp.getHostname().equals(hostname))
				.findFirst().orElseGet(() -> null);
	}
	public void request(String hostname, CommandBlock block) {
		linkRequests.put(hostname, block);
		find(hostname).requestDevice(block, event -> linkRequests.remove(hostname));
	}
	public void register(Computer computer) {
		computers.add(computer);
	}
	@EventHandler
	public void onPluginDisable(PluginDisableEvent e) {
		trackedBlocks.keySet().stream().map(Location::getBlock).forEach(b -> {
			if (b != null && b.getType() != Material.REDSTONE_BLOCK)
				b.setType(Material.REDSTONE_BLOCK);
		});
	}
	public void unregister(Computer computer) {
		computers.remove(computer);
		Iterator<Map.Entry<Location, Computer>> it = trackedBlocks.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Location, Computer> entry = it.next();
			if (entry.getValue() == computer) {
				it.remove();
				Block block = entry.getKey().getBlock();
				if (block != null) {
					block.setType(Material.REDSTONE_BLOCK);
				}
			}
		}
		if (!ComputerData.delete(computer.getHostname()))
			Consoles.getInstance().getLogger().warning("Failed to remove computer: " + computer.getConsole());
	}
}
