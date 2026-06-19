import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {
    private static final List<String> BUILTINS = List.of("echo", "exit", "type", "complete");
    private static final Map<String, String> registeredCompletions = new HashMap<>();
    
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
                
                if (input.contains(" ")) {
                    int firstSpaceIndex = input.indexOf(' ');
                    String primaryCommand = input.substring(0, firstSpaceIndex);

                    // --- CASE A1: REGISTERED COMPLETER SCRIPT EXECUTION ---
                    if (registeredCompletions.containsKey(primaryCommand)) {
                        String scriptPath = registeredCompletions.get(primaryCommand);
                        
                        String[] words = input.split("\\s+");
                        String argv1 = primaryCommand;
                        String argv2 = "";
                        String argv3 = "";

                        if (input.endsWith(" ")) {
                            argv2 = "";
                            argv3 = words.length > 0 ? words[words.length - 1] : "";
                        } else {
                            argv2 = words.length > 0 ? words[words.length - 1] : "";
                            argv3 = words.length > 1 ? words[words.length - 2] : "";
                        }

                        if (input.equals(lastTabPrefix)) {
                            tabCount++;
                        } else {
                            lastTabPrefix = input;
                            tabCount = 1;
                        }

                        try {
                            ProcessBuilder pb = new ProcessBuilder(scriptPath, argv1, argv2, argv3);
                            Map<String, String> env = pb.environment();
                            env.put("COMP_LINE", input);
                            env.put("COMP_POINT", String.valueOf(input.getBytes().length));
                            
                            Process process = pb.start();
                            List<String> scriptCandidates = new ArrayList<>();
                            
                            // UPDATED: Read all lines from stdout into a list of candidates
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (!line.trim().isEmpty()) {
                                        scriptCandidates.add(line.trim());
                                    }
                                }
                                process.waitFor();
                            }

                            if (scriptCandidates.size() == 1) {
                                String candidate = scriptCandidates.get(0);
                                int lastSpaceIndex = input.lastIndexOf(' ');
                                String rawPrefix = input.substring(lastSpaceIndex + 1);
                                
                                String completion = candidate + " ";
                                String addition = completion.substring(rawPrefix.length());
                                
                                System.out.print(addition);
                                System.out.flush();
                                currentLine.append(addition);
                                
                                tabCount = 0;
                                lastTabPrefix = "";
                                continue;
                            } 
                            else if (scriptCandidates.size() > 1) {
                                Collections.sort(scriptCandidates); // Sort alphabetically

                                if (tabCount == 1) {
                                    // First tab rings the bell
                                    System.out.print("\u0007");
                                    System.out.flush();
                                } else if (tabCount >= 2) {
                                    // Second tab prints the matching candidate collection
                                    System.out.println();
                                    for (int i = 0; i < scriptCandidates.size(); i++) {
                                        System.out.print(scriptCandidates.get(i));
                                        if (i < scriptCandidates.size() - 1) {
                                            System.out.print("  "); // Two spaces separation
                                        }
                                    }
                                    System.out.println();
                                    System.out.print("$ " + input);
                                    System.out.flush();
                                    tabCount = 0;
                                }
                                continue;
                            } else {
                                System.out.print("\u0007");
                                System.out.flush();
                                tabCount = 0;
                                lastTabPrefix = "";
                                continue;
                            }
                        } catch (Exception e) {
                            System.out.print("\u0007");
                            System.out.flush();
                            tabCount = 0;
                            lastTabPrefix = "";
                            continue;
                        }
                    }

                    // --- CASE A2: FALLBACK STANDARD ARGUMENT COMPLETION ---
                    int lastSpaceIndex = input.lastIndexOf(' ');
                    String rawPrefix = input.substring(lastSpaceIndex + 1);

                    String dirPath = ".";
                    String filePrefix = rawPrefix;

                    if (rawPrefix.contains("/")) {
                        int lastSlashIndex = rawPrefix.lastIndexOf('/');
                        dirPath = rawPrefix.substring(0, lastSlashIndex + 1); 
                        filePrefix = rawPrefix.substring(lastSlashIndex + 1); 
                    }

                    File searchDir = new File(dirPath);
                    File[] files = searchDir.listFiles();
                    List<File> fileMatches = new ArrayList<>();

                    if (files != null) {
                        for (File file : files) {
                            if (file.getName().startsWith(filePrefix)) {
                                fileMatches.add(file);
                            }
                        }
                    }

                    fileMatches.sort((f1, f2) -> f1.getName().compareTo(f2.getName()));

                    if (fileMatches.size() == 1) {
                        File match = fileMatches.get(0);
                        String matchedName = match.getName();
                        String suffix = match.isDirectory() ? "/" : " ";
                        
                        String completePath = (rawPrefix.contains("/")) ? dirPath + matchedName + suffix : matchedName + suffix;
                        String addition = completePath.substring(rawPrefix.length());
                        
                        System.out.print(addition);
                        System.out.flush();
                        currentLine.append(addition);
                        
                        tabCount = 0;
                        lastTabPrefix = "";
                    } 
                    else if (fileMatches.size() > 1) {
                        List<String> matchNames = new ArrayList<>();
                        for (File f : fileMatches) {
                            matchNames.add(f.getName());
                        }

                        String lcp = findLongestCommonPrefix(matchNames);

                        if (lcp.length() > filePrefix.length()) {
                            String completePath = (rawPrefix.contains("/")) ? dirPath + lcp : lcp;
                            String addition = completePath.substring(rawPrefix.length());
                            
                            System.out.print(addition);
                            System.out.flush();
                            currentLine.append(addition);
                            
                            tabCount = 0;
                            lastTabPrefix = "";
                        } else {
                            if (input.equals(lastTabPrefix)) {
                                tabCount++;
                            } else {
                                lastTabPrefix = input;
                                tabCount = 1;
                            }

                            if (tabCount == 1) {
                                System.out.print("\u0007");
                                System.out.flush();
                            } else if (tabCount >= 2) {
                                System.out.println();
                                for (int i = 0; i < fileMatches.size(); i++) {
                                    File m = fileMatches.get(i);
                                    System.out.print(m.getName() + (m.isDirectory() ? "/" : ""));
                                    if (i < fileMatches.size() - 1) {
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

                // --- CASE B: COMMAND COMPLETION ---
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

            // Any non-tab keystroke resets history state tracking configurations
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

        else if (command.equals("complete")) {
            StringBuilder output = new StringBuilder();
            
            if (parts.length >= 3 && parts[1].equals("-p")) {
                String targetCommand = parts[2];
                if (registeredCompletions.containsKey(targetCommand)) {
                    String scriptPath = registeredCompletions.get(targetCommand);
                    output.append("complete -C '").append(scriptPath).append("' ").append(targetCommand).append(System.lineSeparator());
                } else {
                    output.append("complete: ").append(targetCommand).append(": no completion specification").append(System.lineSeparator());
                }
            } 
            else if (parts.length >= 4 && parts[1].equals("-C")) {
                String scriptPath = parts[2];
                String targetCommand = parts[3];
                registeredCompletions.put(targetCommand, scriptPath);
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