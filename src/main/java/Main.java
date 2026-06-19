import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Main {
    private static final List<String> BUILTINS = List.of("echo", "exit", "type");
    
    private static String lastTabPrefix = "";
    private static int tabCount = 0;

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

    private static String findLongestCommonPrefix(List<String> strs) {
        if (strs == null || strs.isEmpty()) {
            return "";
        }
        String prefix = strs.get(0);
        for (int i = 1; i < strs.size(); i++) {
            while (strs.get(i).indexOf(prefix) != 0) {
                prefix = prefix.substring(0, prefix.length() - 1);
                if (prefix.isEmpty()) {
                    return "";
                }
            }
        }
        return prefix;
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
                
                // --- CASE A: FILENAME ARGUMENT COMPLETION ---
                if (input.contains(" ")) {
                    int lastSpaceIndex = input.lastIndexOf(' ');
                    String prefix = input.substring(lastSpaceIndex + 1);

                    // Scan current working directory for matching files
                    File currentDir = new File(".");
                    File[] files = currentDir.listFiles();
                    List<String> fileMatches = new ArrayList<>();

                    if (files != null) {
                        for (File file : files) {
                            if (file.getName().startsWith(prefix)) {
                                fileMatches.add(file.getName());
                            }
                        }
                    }

                    // For this stage, we only care about exactly 1 single match case
                    if (fileMatches.size() == 1) {
                        String completedFile = fileMatches.get(0) + " ";
                        String addition = completedFile.substring(prefix.length());
                        System.out.print(addition);
                        System.out.flush();
                        currentLine.append(addition);
                    } else {
                        // Ring bell if nothing matches or ambiguous
                        System.out.print("\u0007");
                        System.out.flush();
                    }
                    continue;
                }

                // --- CASE B: COMMAND COMPLETION (Existing Logic) ---
                if (input.isEmpty()) {
                    System.out.print("\u0007");
                    System.out.flush();
                    continue;
                }

                if (input.equals(lastTabPrefix)) {
                    tabCount++;
                } else {
                    lastTabPrefix = input;
                    tabCount = 1;
                }

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

                List<String> matches = new ArrayList<>();
                for (String candidate : candidates) {
                    if (candidate.startsWith(input)) {
                        matches.add(candidate);
                    }
                }

                Collections.sort(matches);

                if (matches.size() == 1) {
                    String completed = matches.get(0) + " ";
                    String addition = completed.substring(input.length());
                    System.out.print(addition);
                    System.out.flush();
                    currentLine.append(addition);
                    tabCount = 0;
                    lastTabPrefix = "";
                } 
                else if (matches.size() > 1) {
                    String lcp = findLongestCommonPrefix(matches);

                    if (lcp.length() > input.length()) {
                        String addition = lcp.substring(input.length());
                        System.out.print(addition);
                        System.out.flush();
                        currentLine.append(addition);
                        tabCount = 0;
                        lastTabPrefix = "";
                    } else {
                        if (tabCount == 1) {
                            System.out.print("\u0007");
                            System.out.flush();
                        } else if (tabCount >= 2) {
                            System.out.println();
                            for (int i = 0; i < matches.size(); i++) {
                                System.out.print(matches.get(i));
                                if (i < matches.size() - 1) {
                                    System.out.print("  ");
                                }
                            }
                            System.out.println();
                            System.out.print("$ " + input);
                            System.out.flush();
                            tabCount = 0;
                        }
                    }
                } 
                else {
                    System.out.print("\u0007");
                    System.out.flush();
                    tabCount = 0;
                    lastTabPrefix = "";
                }
                continue;
            }

            tabCount = 0;
            lastTabPrefix = "";

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