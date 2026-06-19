import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Main {
    private static final List<String> BUILTINS = List.of("echo", "exit", "type");

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
        configureTerminalMode("-icanon -echo");

        try {
            runShellLoop();
        } finally {
            configureTerminalMode("icanon echo");
        }
    }

    private static void runShellLoop() throws Exception {
        StringBuilder currentLine = new StringBuilder();
        System.out.print("$ ");
        System.out.flush();

        InputStream in = System.in;

        while (true) {
            int c = in.read();

            if (c == -1) {
                break;
            }

            // 1. Handle Tab key
            if (c == '\t') {
                String input = currentLine.toString();
                
                if (input.contains(" ") || input.isEmpty()) {
                    System.out.print("\u0007");
                    System.out.flush();
                    continue;
                }

                // Gather candidates dynamically: Builtins + PATH executables
                Set<String> candidates = new HashSet<>(BUILTINS);
                
                String pathEnv = System.getenv("PATH");
                if (pathEnv != null) {
                    String[] paths = pathEnv.split(File.pathSeparator);
                    for (String path : paths) {
                        File dir = new File(path);
                        if (dir.exists() && dir.isDirectory()) {
                            File[] files = dir.listFiles();
                            if (files != null) {
                                for (File file : files) {
                                    if (file.isFile() && file.canExecute()) {
                                        candidates.add(file.getName());
                                    }
                                }
                            }
                        }
                    }
                }

                // Check for matches starting with the user's input
                List<String> matches = new ArrayList<>();
                for (String candidate : candidates) {
                    if (candidate.startsWith(input)) {
                        matches.add(candidate);
                    }
                }

                // If exactly one match is found, autocomplete it with a trailing space
                if (matches.size() == 1) {
                    String completed = matches.get(0) + " ";
                    String addition = completed.substring(input.length());
                    System.out.print(addition);
                    System.out.flush();
                    currentLine.append(addition);
                } else {
                    // No matches or multiple matches -> ring the terminal bell
                    System.out.print("\u0007");
                    System.out.flush();
                }
                continue;
            }

            // 2. Handle Newline
            if (c == '\n' || c == '\r') {
                System.out.println();
                String commandLine = currentLine.toString();

                processCommandLine(commandLine);

                currentLine.setLength(0);
                System.out.print("$ ");
                System.out.flush();
                continue;
            }

            // 3. Handle Backspace
            if (c == 127 || c == 8) {
                if (currentLine.length() > 0) {
                    currentLine.deleteCharAt(currentLine.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }
                continue;
            }

            // 4. Handle standard typing input
            char ch = (char) c;
            System.out.print(ch);
            System.out.flush();
            currentLine.append(ch);
        }
    }

    private static void processCommandLine(String input) throws Exception {
        String[] parts = parseCommand(input);

        if (parts.length == 0) {
            return;
        }

        String stdoutFile = null;
        String stderrFile = null;

        boolean stdoutAppend = false;
        boolean stderrAppend = false;

        List<String> commandParts = new ArrayList<>();

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(">") || parts[i].equals("1>")) {
                if (i + 1 < parts.length) {
                    stdoutFile = parts[i + 1];
                    stdoutAppend = false;
                }
                i++;
            }

            else if (parts[i].equals(">>") || parts[i].equals("1>>")) {
                if (i + 1 < parts.length) {
                    stdoutFile = parts[i + 1];
                    stdoutAppend = true;
                }
                i++;
            }

            else if (parts[i].equals("2>")) {
                if (i + 1 < parts.length) {
                    stderrFile = parts[i + 1];
                    stderrAppend = false;
                }
                i++;
            }

            else if (parts[i].equals("2>>")) {
                if (i + 1 < parts.length) {
                    stderrFile = parts[i + 1];
                    stderrAppend = true;
                }
                i++;
            }

            else {
                commandParts.add(parts[i]);
            }
        }

        parts = commandParts.toArray(new String[0]);

        if (parts.length == 0) {
            return;
        }

        String command = parts[0];

        if (command.equals("exit")) {
            configureTerminalMode("icanon echo");
            System.exit(0);
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

            if (stdoutFile != null) {
                try (FileWriter writer = new FileWriter(stdoutFile, stdoutAppend)) {
                    writer.write(output.toString());
                }
            } else {
                System.out.print(output);
            }

            if (stderrFile != null) {
                new FileWriter(stderrFile, stderrAppend).close();
            }
        }

        else if (command.equals("type")) {
            if (parts.length < 2) {
                return;
            }
            String target = parts[1];
            StringBuilder output = new StringBuilder();

            if (BUILTINS.contains(target)) {
                output.append(target).append(" is a shell builtin").append(System.lineSeparator());
            } else {
                String pathEnv = System.getenv("PATH");
                String[] paths = pathEnv != null ? pathEnv.split(File.pathSeparator) : new String[0];
                boolean pathFound = false;

                for (String path : paths) {
                    File file = new File(path, target);
                    if (file.exists() && file.canExecute()) {
                        output.append(target).append(" is ").append(file.getAbsolutePath()).append(System.lineSeparator());
                        pathFound = true;
                        break;
                    }
                }

                if (!pathFound) {
                    output.append(target).append(": not found").append(System.lineSeparator());
                }
            }

            if (stdoutFile != null) {
                try (FileWriter writer = new FileWriter(stdoutFile, stdoutAppend)) {
                    writer.write(output.toString());
                }
            } else {
                System.out.print(output);
            }

            if (stderrFile != null) {
                new FileWriter(stderrFile, stderrAppend).close();
            }
        }

        else {
            String pathEnv = System.getenv("PATH");
            String[] paths = pathEnv != null ? pathEnv.split(File.pathSeparator) : new String[0];

            boolean found = false;

            for (String path : paths) {
                File file = new File(path, command);

                if (file.exists() && file.canExecute()) {
                    ProcessBuilder pb = new ProcessBuilder(Arrays.asList(parts));
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                    if (stdoutFile != null) {
                        if (stdoutAppend) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(stdoutFile)));
                        } else {
                            pb.redirectOutput(new File(stdoutFile));
                        }
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (stderrFile != null) {
                        if (stderrAppend) {
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(stderrFile)));
                        } else {
                            pb.redirectError(new File(stderrFile));
                        }
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process process = pb.start();
                    process.waitFor();

                    found = true;
                    break;
                }
            }

            if (!found) {
                String errorMessage = command + ": command not found";

                if (stderrFile != null) {
                    try (FileWriter writer = new FileWriter(stderrFile, stderrAppend)) {
                        writer.write(errorMessage + System.lineSeparator());
                    }
                } else {
                    System.out.println(errorMessage);
                }
            }
        }
    }

    private static void configureTerminalMode(String args) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty");
            pb.inheritIO().start().waitFor();
        } catch (Exception e) {
            // Ignore if running without an accessible TTY device
        }
    }
}