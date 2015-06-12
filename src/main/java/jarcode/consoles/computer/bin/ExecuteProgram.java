package jarcode.consoles.computer.bin;

import jarcode.consoles.Consoles;
import jarcode.consoles.computer.Computer;
import jarcode.consoles.computer.filesystem.FSProvidedProgram;
import jarcode.consoles.computer.interpreter.InterpretedProgram;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static jarcode.consoles.computer.ProgramUtils.*;

@Manual(
		author = "Jarcode",
		version = "1.3",
		contents = "Executes a program provided by the server. These programs must exist " +
				"in the plugin folder. You can specify programs with a path relative to the " +
				"plugin folder, and you do not need to add a .lua suffix to the name."
)
public class ExecuteProgram extends FSProvidedProgram {
	@Override
	public void run(String str, Computer computer) throws Exception {
		sleep(200);
		int i = str.indexOf(" ");
		if (i == -1)
			i = str.length();
		String path = str.substring(0, i);
		String args = str.substring(i);
		if (!path.endsWith(".lua"))
			path += ".lua";

		File file = new File(Consoles.getInstance().getDataFolder().getAbsolutePath()
				+ File.separatorChar + path);
		if (!file.exists() || file.isDirectory()) {
			return;
		}
		try {
			String program = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
			InterpretedProgram.exec(program, computer, in, out, args);
		}
		catch (IOException e) {
			println("Failed to read lua program from plugin folder: " + path);
			print(e.getClass().getSimpleName());
		}
	}
}
