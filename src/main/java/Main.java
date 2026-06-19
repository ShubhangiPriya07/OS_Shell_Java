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
    private static final List<String> BUILTINS = List.of("echo", "exit", "type", "complete", "jobs");
    private static final Map<String, String> registeredCompletions = new HashMap<>();

    private static class Job {
        int id;
        long pid;
        String command;
        String status;
        Process process;

        public Job(int id, long pid, String command, String status, Process process) {
            this.id = id;
            this.pid = pid;
            this.command = command;
            this.status = status;
            this.process = process;
        }
    }

    private static final List<Job> backgroundJobs = new ArrayList<>();

    private static String lastTabPrefix = "";
    private static int tabCount = 0;

    private static int getNextJobId() {
        if (backgroundJobs.isEmpty()) {
            return 1;
        }
        int max = 0;
        for (Job job : backgroundJobs) {
            if (job.id > max) max = job.id;
        }
        return max + 1;
    }

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
            } else if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args.toArray(new String[0]);
    }

    // Split a raw input line into pipeline segments on unquoted '|'
    private static List<String> splitOnPipe(String input) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                current.append(c);
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                current.append(c);
            } else if (c == '|' && !inSingleQuotes && !inDoubleQuotes) {
                segments.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        segments.add(current.toString().trim());
        return segments;
    }

    private static String findLongestCommonPrefix(List<String> strs) {
        if (strs == null || strs.isEmpty()) return "";
        String prefix = strs.get(0);
        for (int i = 1; i < strs.size(); i++) {
            while (strs.get(i).indexOf(prefix) != 0) {
                prefix = prefix.substring(0, prefix.length() - 1);
                if (prefix.isEmpty()) return "";
            }
        }
        return prefix;
    }

    private static void updateJobStatuses() {
        for (Job job : backgroundJobs) {
            if (job.status.equals("Running") && !job.process.isAlive()) {
                job.status = "Done";
                if (job.command.endsWith(" &")) {
                    job.command = job.command.substring(0, job.command.length() - 2);
                }
            }
        }
    }

    private static String reapBeforePrompt() {
        updateJobStatuses();
        StringBuilder output = new StringBuilder();
        int totalJobs = backgroundJobs.size();

        for (int i = 0; i < totalJobs; i++) {
            Job job = backgroundJobs.get(i);
            if (job.status.equals("Done")) {
                char marker = ' ';
                if (i == totalJobs - 1) marker = '+';
                else if (i == totalJobs - 2) marker = '-';
                output.append(String.format("[%d]%c  %-24s%s%n", job.id, marker, job.status, job.command));
            }
        }

        backgroundJobs.removeIf(job -> job.status.equals("Done"));
        return output.toString();
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

        System.out.print(reapBeforePrompt());
        System.out.print("$ ");
        System.out.flush();

        InputStream in = System.in;

        while (true) {
            int c = in.read();
            if (c == -1) break;

            // 1. Handle Tab key
            if (c == '\t') {
                String input = currentLine.toString();

                if (input.contains(" ")) {
                    int firstSpaceIndex = input.indexOf(' ');
                    String primaryCommand = input.substring(0, firstSpaceIndex);

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

                        try {
                            ProcessBuilder pb = new ProcessBuilder(scriptPath, argv1, argv2, argv3);
                            Map<String, String> env = pb.environment();
                            env.put("COMP_LINE", input);
                            env.put("COMP_POINT", String.valueOf(input.getBytes().length));

                            Process process = pb.start();
                            List<String> scriptCandidates = new ArrayList<>();

                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (!line.trim().isEmpty()) scriptCandidates.add(line.trim());
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
                            } else if (scriptCandidates.size() > 1) {
                                String lcp = findLongestCommonPrefix(scriptCandidates);
                                if (lcp.length() > argv2.length()) {
                                    String addition = lcp.substring(argv2.length());
                                    System.out.print(addition);
                                    System.out.flush();
                                    currentLine.append(addition);
                                    tabCount = 0;
                                    lastTabPrefix = "";
                                } else {
                                    if (input.equals(lastTabPrefix)) tabCount++;
                                    else { lastTabPrefix = input; tabCount = 1; }

                                    if (tabCount == 1) {
                                        System.out.print("\u0007");
                                        System.out.flush();
                                    } else if (tabCount >= 2) {
                                        Collections.sort(scriptCandidates);
                                        System.out.println();
                                        for (int i = 0; i < scriptCandidates.size(); i++) {
                                            System.out.print(scriptCandidates.get(i));
                                            if (i < scriptCandidates.size() - 1) System.out.print("  ");
                                        }
                                        System.out.println();
                                        System.out.print("$ " + input);
                                        System.out.flush();
                                        tabCount = 0;
                                    }
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
                            if (file.getName().startsWith(filePrefix)) fileMatches.add(file);
                        }
                    }

                    fileMatches.sort((f1, f2) -> f1.getName().compareTo(f2.getName()));

                    if (fileMatches.size() == 1) {
                        File match = fileMatches.get(0);
                        String matchedName = match.getName();
                        String suffix = match.isDirectory() ? "/" : " ";
                        String completePath = rawPrefix.contains("/") ? dirPath + matchedName + suffix : matchedName + suffix;
                        String addition = completePath.substring(rawPrefix.length());
                        System.out.print(addition);
                        System.out.flush();
                        currentLine.append(addition);
                        tabCount = 0;
                        lastTabPrefix = "";
                    } else if (fileMatches.size() > 1) {
                        List<String> matchNames = new ArrayList<>();
                        for (File f : fileMatches) matchNames.add(f.getName());
                        String lcp = findLongestCommonPrefix(matchNames);

                        if (lcp.length() > filePrefix.length()) {
                            String completePath = rawPrefix.contains("/") ? dirPath + lcp : lcp;
                            String addition = completePath.substring(rawPrefix.length());
                            System.out.print(addition);
                            System.out.flush();
                            currentLine.append(addition);
                            tabCount = 0;
                            lastTabPrefix = "";
                        } else {
                            if (input.equals(lastTabPrefix)) ++tabCount;
                            else { lastTabPrefix = input; tabCount = 1; }

                            if (tabCount == 1) {
                                System.out.print("\u0007");
                                System.out.flush();
                            } else if (tabCount >= 2) {
                                System.out.println();
                                for (int i = 0; i < fileMatches.size(); i++) {
                                    File m = fileMatches.get(i);
                                    System.out.print(m.getName() + (m.isDirectory() ? "/" : ""));
                                    if (i < fileMatches.size() - 1) System.out.print("  ");
                                }
                                System.out.println();
                                System.out.print("$ " + input);
                                System.out.flush();
                                tabCount = 0;
                            }
                        }
                    } else {
                        System.out.print("\u0007");
                        System.out.flush();
                        tabCount = 0;
                        lastTabPrefix = "";
                    }
                    continue;
                }

                if (input.isEmpty()) {
                    System.out.print("\u0007");
                    System.out.flush();
                    continue;
                }

                if (input.equals(lastTabPrefix)) tabCount++;
                else { lastTabPrefix = input; tabCount = 1; }

                Set<String> candidates = new HashSet<>();
                candidates.addAll(BUILTINS);
                String pathEnv = System.getenv("PATH");
                if (pathEnv != null) {
                    for (String path : pathEnv.split(File.pathSeparator)) {
                        File dir = new File(path);
                        if (dir.exists() && dir.isDirectory()) {
                            File[] dirFiles = dir.listFiles();
                            if (dirFiles != null) {
                                for (File file : dirFiles) {
                                    if (file.isFile() && file.canExecute()) candidates.add(file.getName());
                                }
                            }
                        }
                    }
                }

                List<String> matches = new ArrayList<>();
                for (String candidate : candidates) {
                    if (candidate.startsWith(input)) matches.add(candidate);
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
                } else if (matches.size() > 1) {
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
                                if (i < matches.size() - 1) System.out.print("  ");
                            }
                            System.out.println();
                            System.out.print("$ " + input);
                            System.out.flush();
                            tabCount = 0;
                        }
                    }
                } else {
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
                System.out.print(reapBeforePrompt());
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
        // Check for pipeline
        List<String> pipeSegments = splitOnPipe(input);

        if (pipeSegments.size() > 1) {
            executePipeline(pipeSegments);
            return;
        }

        // Single command path
        String[] parts = parseCommand(input);
        if (parts.length == 0) return;

        String stdoutFile = null;
        String stderrFile = null;
        boolean stdoutAppend = false;
        boolean stderrAppend = false;
        List<String> commandParts = new ArrayList<>();

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(">") || parts[i].equals("1>")) {
                if (i + 1 < parts.length) { stdoutFile = parts[i + 1]; stdoutAppend = false; }
                i++;
            } else if (parts[i].equals(">>") || parts[i].equals("1>>")) {
                if (i + 1 < parts.length) { stdoutFile = parts[i + 1]; stdoutAppend = true; }
                i++;
            } else if (parts[i].equals("2>")) {
                if (i + 1 < parts.length) { stderrFile = parts[i + 1]; stderrAppend = false; }
                i++;
            } else if (parts[i].equals("2>>")) {
                if (i + 1 < parts.length) { stderrFile = parts[i + 1]; stderrAppend = true; }
                i++;
            } else {
                commandParts.add(parts[i]);
            }
        }

        parts = commandParts.toArray(new String[0]);
        if (parts.length == 0) return;

        boolean isBackgroundJob = false;
        if (parts[parts.length - 1].equals("&")) {
            isBackgroundJob = true;
            parts = Arrays.copyOf(parts, parts.length - 1);
        }
        if (parts.length == 0) return;

        String command = parts[0];

        if (command.equals("exit")) {
            configureTerminalMode("icanon echo");
            System.exit(0);
        } else if (command.equals("echo")) {
            StringBuilder output = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) output.append(" ");
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
            if (stderrFile != null) new FileWriter(stderrFile, stderrAppend).close();

        } else if (command.equals("type")) {
            if (parts.length < 2) return;
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
                if (!pathFound) output.append(target).append(": not found").append(System.lineSeparator());
            }

            if (stdoutFile != null) {
                try (FileWriter writer = new FileWriter(stdoutFile, stdoutAppend)) {
                    writer.write(output.toString());
                }
            } else {
                System.out.print(output);
            }
            if (stderrFile != null) new FileWriter(stderrFile, stderrAppend).close();

        } else if (command.equals("complete")) {
            StringBuilder output = new StringBuilder();

            if (parts.length >= 3 && parts[1].equals("-p")) {
                String targetCommand = parts[2];
                if (registeredCompletions.containsKey(targetCommand)) {
                    String scriptPath = registeredCompletions.get(targetCommand);
                    output.append("complete -C '").append(scriptPath).append("' ").append(targetCommand).append(System.lineSeparator());
                } else {
                    output.append("complete: ").append(targetCommand).append(": no completion specification").append(System.lineSeparator());
                }
            } else if (parts.length >= 4 && parts[1].equals("-C")) {
                registeredCompletions.put(parts[3], parts[2]);
            } else if (parts.length >= 3 && parts[1].equals("-r")) {
                registeredCompletions.remove(parts[2]);
            }

            if (stdoutFile != null) {
                try (FileWriter writer = new FileWriter(stdoutFile, stdoutAppend)) {
                    writer.write(output.toString());
                }
            } else {
                System.out.print(output);
            }
            if (stderrFile != null) new FileWriter(stderrFile, stderrAppend).close();

        } else if (command.equals("jobs")) {
            updateJobStatuses();
            StringBuilder output = new StringBuilder();
            int totalJobs = backgroundJobs.size();

            for (int i = 0; i < totalJobs; i++) {
                Job job = backgroundJobs.get(i);
                char marker = ' ';
                if (i == totalJobs - 1) marker = '+';
                else if (i == totalJobs - 2) marker = '-';
                output.append(String.format("[%d]%c  %-24s%s%n", job.id, marker, job.status, job.command));
            }
            backgroundJobs.removeIf(job -> job.status.equals("Done"));

            if (stdoutFile != null) {
                try (FileWriter writer = new FileWriter(stdoutFile, stdoutAppend)) {
                    writer.write(output.toString());
                }
            } else {
                System.out.print(output);
            }
            if (stderrFile != null) new FileWriter(stderrFile, stderrAppend).close();

        } else {
            String pathEnv = System.getenv("PATH");
            String[] paths = pathEnv != null ? pathEnv.split(File.pathSeparator) : new String[0];
            boolean found = false;

            for (String path : paths) {
                File file = new File(path, command);
                if (file.exists() && file.canExecute()) {
                    ProcessBuilder pb = new ProcessBuilder(Arrays.asList(parts));
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                    if (stdoutFile != null) {
                        pb.redirectOutput(stdoutAppend
                            ? ProcessBuilder.Redirect.appendTo(new File(stdoutFile))
                            : ProcessBuilder.Redirect.to(new File(stdoutFile)));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (stderrFile != null) {
                        pb.redirectError(stderrAppend
                            ? ProcessBuilder.Redirect.appendTo(new File(stderrFile))
                            : ProcessBuilder.Redirect.to(new File(stderrFile)));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process process = pb.start();

                    if (isBackgroundJob) {
                        backgroundJobs.removeIf(job -> job.status.equals("Done"));
                        updateJobStatuses();
                        backgroundJobs.removeIf(job -> job.status.equals("Done"));

                        int newJobId = getNextJobId();
                        System.out.println("[" + newJobId + "] " + process.pid());

                        StringBuilder fullCmd = new StringBuilder();
                        for (int i = 0; i < parts.length; i++) {
                            if (i > 0) fullCmd.append(" ");
                            fullCmd.append(parts[i]);
                        }
                        fullCmd.append(" &");
                        backgroundJobs.add(new Job(newJobId, process.pid(), fullCmd.toString(), "Running", process));
                    } else {
                        process.waitFor();
                    }

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

    // Execute a pipeline of two or more external commands
    private static void executePipeline(List<String> segments) throws Exception {
        int n = segments.size();
        List<Process> processes = new ArrayList<>();

        Process prevProcess = null;

        for (int i = 0; i < n; i++) {
            String[] parts = parseCommand(segments.get(i));
            if (parts.length == 0) continue;

            String command = parts[0];
            String execPath = resolveCommand(command);
            if (execPath == null) {
                System.out.println(command + ": command not found");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(Arrays.asList(parts));
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            // First command: read from terminal
            if (i == 0) {
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            } else {
                // Pipe stdin from previous process's stdout
                pb.redirectInput(prevProcess.inputReader().equals(null)
                    ? ProcessBuilder.Redirect.INHERIT
                    : ProcessBuilder.Redirect.PIPE);
            }

            // Last command: write to terminal
            if (i == n - 1) {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            Process process;

            if (i == 0) {
                // Start first process normally
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                process = pb.start();
            } else {
                // Connect stdout of previous to stdin of this process
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                process = pb.start();

                // Pipe data from prevProcess stdout -> this process stdin in a thread
                final Process src = prevProcess;
                final Process dst = process;
                Thread piper = new Thread(() -> {
                    try (InputStream from = src.getInputStream();
                         var to = dst.getOutputStream()) {
                        byte[] buf = new byte[4096];
                        int read;
                        while ((read = from.read(buf)) != -1) {
                            to.write(buf, 0, read);
                            to.flush();
                        }
                    } catch (IOException e) {
                        // pipe closed normally
                    }
                });
                piper.setDaemon(true);
                piper.start();
            }

            processes.add(process);
            prevProcess = process;
        }

        // Wait for all processes to finish
        for (Process p : processes) {
            p.waitFor();
        }
    }

    // Resolve a command name to its full path via PATH
    private static String resolveCommand(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String path : pathEnv.split(File.pathSeparator)) {
            File file = new File(path, command);
            if (file.exists() && file.canExecute()) return file.getAbsolutePath();
        }
        return null;
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