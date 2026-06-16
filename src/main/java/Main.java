import java.io.File;
import java.io.FileWriter;
import java.util.Scanner;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static String[] parseCommand(String input) {
        List<String> args = new ArrayList<>();

        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inDoubleQuotes && c == '\\') {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);

                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append('\\');
                    }
                } else {
                    current.append('\\');
                }
            }

            else if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            }

            else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            }

            else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            }

            else if (Character.isWhitespace(c)
                    && !inSingleQuotes
                    && !inDoubleQuotes) {

                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            }

            else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args.toArray(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String input = sc.nextLine();

            String[] parts = parseCommand(input);

            if (parts.length == 0) {
                continue;
            }

            String outputFile = null;
            List<String> commandParts = new ArrayList<>();

            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals(">") || parts[i].equals("1>")) {
                    if (i + 1 < parts.length) {
                        outputFile = parts[i + 1];
                    }
                    break;
                }
                commandParts.add(parts[i]);
            }

            parts = commandParts.toArray(new String[0]);

            if (parts.length == 0) {
                continue;
            }

            String command = parts[0];

            if (command.equals("exit")) {
                break;
            }

            else if (command.equals("echo")) {
                StringBuilder output = new StringBuilder();

                for (int i = 1; i < parts.length; i++) {
                    if (i > 1) {
                        output.append(" ");
                    }
                    output.append(parts[i]);
                }

                output.append(System.lineSeparator());

                if (outputFile != null) {
                    try (FileWriter writer = new FileWriter(outputFile, false)) {
                        writer.write(output.toString());
                    }
                } else {
                    System.out.print(output);
                }
            }

            else if (command.equals("type")) {
                if (parts.length < 2) {
                    continue;
                }

                String cmd = parts[1];
                String result;

                if (cmd.equals("echo")
                        || cmd.equals("exit")
                        || cmd.equals("type")) {

                    result = cmd + " is a shell builtin";
                } else {

                    String pathEnv = System.getenv("PATH");
                    String[] paths = pathEnv.split(File.pathSeparator);

                    result = cmd + ": not found";

                    for (String path : paths) {
                        File file = new File(path, cmd);

                        if (file.exists() && file.canExecute()) {
                            result = cmd + " is " + file.getAbsolutePath();
                            break;
                        }
                    }
                }

                if (outputFile != null) {
                    try (FileWriter writer = new FileWriter(outputFile, false)) {
                        writer.write(result + System.lineSeparator());
                    }
                } else {
                    System.out.println(result);
                }
            }

            else {
                String pathEnv = System.getenv("PATH");
                String[] paths = pathEnv.split(File.pathSeparator);

                boolean found = false;

                for (String path : paths) {
                    File file = new File(path, command);

                    if (file.exists() && file.canExecute()) {

                        ProcessBuilder pb =
                                new ProcessBuilder(Arrays.asList(parts));

                        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                        if (outputFile != null) {
                            pb.redirectOutput(new File(outputFile));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }

                        Process process = pb.start();
                        process.waitFor();

                        found = true;
                        break;
                    }
                }

                if (!found) {
                    System.out.println(command + ": command not found");
                }
            }
        }

        sc.close();
    }
}