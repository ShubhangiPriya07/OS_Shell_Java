import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
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
        if (backgroundJobs.isEmpty()) return 1;
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
                    if (next == '"' || next == '\\') { current.append(next); i++; }
                    else current.append('\\');
                } else current.append('\\');
            } else if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) { current.append(input.charAt(i + 1)); i++; }
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) { args.add(current.toString()); current.setLength(0); }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) args.add(current.toString());
        return args.toArray(new String[0]);
    }

    private static List<String> splitOnPipe(String input) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'' && !inDoubleQuotes) { inSingleQuotes = !inSingleQuotes; current.append(c); }
            else if (c == '"' && !inSingleQuotes) { inDoubleQuotes = !inDoubleQuotes; current.append(c); }
            else if (c == '|' && !inSingleQuotes && !inDoubleQuotes) {
                segments.add(current.toString().trim());
                current.setLength(0);
            } else current.append(c);
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
                if (job.command.endsWith(" &"))
                    job.command = job.command.substring(0, job.command.length() - 2);
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
                                while ((line = reader.readLine()) != null)
                                    if (!line.trim().isEmpty()) scriptCandidates.add(line.trim());
                                process.waitFor();
                            }

                            if (scriptCandidates.size() == 1) {
                                String candidate = scriptCandidates.get(0);
                                int lastSpaceIndex = input.lastIndexOf(' ');
                                String rawPrefix = input.substring(lastSpaceIndex + 1);
                                String addition = (candidate + " ").substring(rawPrefix.length());
                                System.out.print(addition); System.out.flush();
                                currentLine.append(addition);
                                tabCount = 0; lastTabPrefix = "";
                                continue;
                            } else if (scriptCandidates.size() > 1) {
                                String lcp = findLongestCommonPrefix(scriptCandidates);
                                if (lcp.length() > argv2.length()) {
                                    String addition = lcp.substring(argv2.length());
                                    System.out.print(addition); System.out.flush();
                                    currentLine.append(addition);
                                    tabCount = 0; lastTabPrefix = "";
                                } else {
                                    if (input.equals(lastTabPrefix)) tabCount++;
                                    else { lastTabPrefix = input; tabCount = 1; }
                                    if (tabCount == 1) { System.out.print("\u0007"); System.out.flush(); }
                                    else if (tabCount >= 2) {
                                        Collections.sort(scriptCandidates);
                                        System.out.println();
                                        for (int i = 0; i < scriptCandidates.size(); i++) {
                                            System.out.print(scriptCandidates.get(i));
                                            if (i < scriptCandidates.size() - 1) System.out.print("  ");
                                        }
                                        System.out.println();
                                        System.out.print("$ " + input); System.out.flush();
                                        tabCount = 0;
                                    }
                                }
                                continue;
                            } else {
                                System.out.print("\u0007"); System.out.flush();
                                tabCount = 0; lastTabPrefix = ""; continue;
                            }
                        } catch (Exception e) {
                            System.out.print("\u0007"); System.out.flush();
                            tabCount = 0; lastTabPrefix = ""; continue;
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
                    if (files != null)
                        for (File file : files)
                            if (file.getName().startsWith(filePrefix)) fileMatches.add(file);
                    fileMatches.sort((f1, f2) -> f1.getName().compareTo(f2.getName()));

                    if (fileMatches.size() == 1) {
                        File match = fileMatches.get(0);
                        String suffix = match.isDirectory() ? "/" : " ";
                        String completePath = rawPrefix.contains("/") ? dirPath + match.getName() + suffix : match.getName() + suffix;
                        String addition = completePath.substring(rawPrefix.length());
                        System.out.print(addition); System.out.flush();
                        currentLine.append(addition);
                        tabCount = 0; lastTabPrefix = "";
                    } else if (fileMatches.size() > 1) {
                        List<String> matchNames = new ArrayList<>();
                        for (File f : fileMatches) matchNames.add(f.getName());
                        String lcp = findLongestCommonPrefix(matchNames);
                        if (lcp.length() > filePrefix.length()) {
                            String completePath = rawPrefix.contains("/") ? dirPath + lcp : lcp;
                            String addition = completePath.substring(rawPrefix.length());
                            System.out.print(addition); System.out.flush();
                            currentLine.append(addition);
                            tabCount = 0; lastTabPrefix = "";
                        } else {
                            if (input.equals(lastTabPrefix)) ++tabCount;
                            else { lastTabPrefix = input; tabCount = 1; }
                            if (tabCount == 1) { System.out.print("\u0007"); System.out.flush(); }
                            else if (tabCount >= 2) {
                                System.out.println();
                                for (int i = 0; i < fileMatches.size(); i++) {
                                    File m = fileMatches.get(i);
                                    System.out.print(m.getName() + (m.isDirectory() ? "/" : ""));
                                    if (i < fileMatches.size() - 1) System.out.print("  ");
                                }
                                System.out.println();
                                System.out.print("$ " + input); System.out.flush();
                                tabCount = 0;
                            }
                        }
                    } else {
                        System.out.print("\u0007"); System.out.flush();
                        tabCount = 0; lastTabPrefix = "";
                    }
                    continue;
                }

                if (input.isEmpty()) { System.out.print("\u0007"); System.out.flush(); continue; }

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
                            if (dirFiles != null)
                                for (File file : dirFiles)
                                    if (file.isFile() && file.canExecute()) candidates.add(file.getName());
                        }
                    }
                }

                List<String> matches = new ArrayList<>();
                for (String candidate : candidates)
                    if (candidate.startsWith(input)) matches.add(candidate);
                Collections.sort(matches);

                if (matches.size() == 1) {
                    String addition = (matches.get(0) + " ").substring(input.length());
                    System.out.print(addition); System.out.flush();
                    currentLine.append(addition);
                    tabCount = 0; lastTabPrefix = "";
                } else if (matches.size() > 1) {
                    String lcp = findLongestCommonPrefix(matches);
                    if (lcp.length() > input.length()) {
                        String addition = lcp.substring(input.length());
                        System.out.print(addition); System.out.flush();
                        currentLine.append(addition);
                        tabCount = 0; lastTabPrefix = "";
                    } else {
                        if (tabCount == 1) { System.out.print("\u0007"); System.out.flush(); }
                        else if (tabCount >= 2) {
                            System.out.println();
                            for (int i = 0; i < matches.size(); i++) {
                                System.out.print(matches.get(i));
                                if (i < matches.size() - 1) System.out.print("  ");
                            }
                            System.out.println();
                            System.out.print("$ " + input); System.out.flush();
                            tabCount = 0;
                        }
                    }
                } else {
                    System.out.print("\u0007"); System.out.flush();
                    tabCount = 0; lastTabPrefix = "";
                }
                continue;
            }

            tabCount = 0;
            lastTabPrefix = "";

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

            if (c == 127 || c == 8) {
                if (currentLine.length() > 0) {
                    currentLine.deleteCharAt(currentLine.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }
                continue;
            }

            char ch = (char) c;
            System.out.print(ch);
            System.out.flush();
            currentLine.append(ch);
        }
    }

    private static void processCommandLine(String input) throws Exception {
        List<String> pipeSegments = splitOnPipe(input);

        if (pipeSegments.size() > 1) {
            executePipeline(pipeSegments);
            return;
        }

        String[] parts = parseCommand(input);
        if (parts.length == 0) return;

        String stdoutFile = null;
        String stderrFile = null;
        boolean stdoutAppend = false;
        boolean stderrAppend = false;
        List<String> commandParts = new ArrayList<>();

        for (int i = 0; i < parts.length; i++) {
            if ((parts[i].equals(">") || parts[i].equals("1>")) && i + 1 < parts.length) {
                stdoutFile = parts[++i]; stdoutAppend = false;
            } else if ((parts[i].equals(">>") || parts[i].equals("1>>")) && i + 1 < parts.length) {
                stdoutFile = parts[++i]; stdoutAppend = true;
            } else if (parts[i].equals("2>") && i + 1 < parts.length) {
                stderrFile = parts[++i]; stderrAppend = false;
            } else if (parts[i].equals("2>>") && i + 1 < parts.length) {
                stderrFile = parts[++i]; stderrAppend = true;
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
                try (FileWriter w = new FileWriter(stdoutFile, stdoutAppend)) { w.write(output.toString()); }
            } else System.out.print(output);
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
                boolean found = false;
                for (String path : paths) {
                    File file = new File(path, target);
                    if (file.exists() && file.canExecute()) {
                        output.append(target).append(" is ").append(file.getAbsolutePath()).append(System.lineSeparator());
                        found = true; break;
                    }
                }
                if (!found) output.append(target).append(": not found").append(System.lineSeparator());
            }
            if (stdoutFile != null) {
                try (FileWriter w = new FileWriter(stdoutFile, stdoutAppend)) { w.write(output.toString()); }
            } else System.out.print(output);
            if (stderrFile != null) new FileWriter(stderrFile, stderrAppend).close();

        } else if (command.equals("complete")) {
            StringBuilder output = new StringBuilder();
            if (parts.length >= 3 && parts[1].equals("-p")) {
                String targetCommand = parts[2];
                if (registeredCompletions.containsKey(targetCommand)) {
                    output.append("complete -C '").append(registeredCompletions.get(targetCommand)).append("' ").append(targetCommand).append(System.lineSeparator());
                } else {
                    output.append("complete: ").append(targetCommand).append(": no completion specification").append(System.lineSeparator());
                }
            } else if (parts.length >= 4 && parts[1].equals("-C")) {
                registeredCompletions.put(parts[3], parts[2]);
            } else if (parts.length >= 3 && parts[1].equals("-r")) {
                registeredCompletions.remove(parts[2]);
            }
            if (stdoutFile != null) {
                try (FileWriter w = new FileWriter(stdoutFile, stdoutAppend)) { w.write(output.toString()); }
            } else System.out.print(output);
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
                try (FileWriter w = new FileWriter(stdoutFile, stdoutAppend)) { w.write(output.toString()); }
            } else System.out.print(output);
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
                    if (stdoutFile != null) pb.redirectOutput(stdoutAppend ? ProcessBuilder.Redirect.appendTo(new File(stdoutFile)) : ProcessBuilder.Redirect.to(new File(stdoutFile)));
                    else pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    if (stderrFile != null) pb.redirectError(stderrAppend ? ProcessBuilder.Redirect.appendTo(new File(stderrFile)) : ProcessBuilder.Redirect.to(new File(stderrFile)));
                    else pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                    Process process = pb.start();
                    if (isBackgroundJob) {
                        backgroundJobs.removeIf(job -> job.status.equals("Done"));
                        updateJobStatuses();
                        backgroundJobs.removeIf(job -> job.status.equals("Done"));
                        int newJobId = getNextJobId();
                        System.out.println("[" + newJobId + "] " + process.pid());
                        StringBuilder fullCmd = new StringBuilder();
                        for (int i = 0; i < parts.length; i++) { if (i > 0) fullCmd.append(" "); fullCmd.append(parts[i]); }
                        fullCmd.append(" &");
                        backgroundJobs.add(new Job(newJobId, process.pid(), fullCmd.toString(), "Running", process));
                    } else {
                        process.waitFor();
                    }
                    found = true; break;
                }
            }
            if (!found) {
                String errorMessage = command + ": command not found";
                if (stderrFile != null) {
                    try (FileWriter w = new FileWriter(stderrFile, stderrAppend)) { w.write(errorMessage + System.lineSeparator()); }
                } else System.out.println(errorMessage);
            }
        }
    }

    // Run a builtin and write its output to the given PrintStream
    private static void runBuiltinToStream(String[] parts, InputStream stdinStream, PrintStream out) throws Exception {
        String command = parts[0];

        if (command.equals("echo")) {
            StringBuilder output = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) output.append(" ");
                output.append(parts[i]);
            }
            out.println(output);

        } else if (command.equals("type")) {
            if (parts.length < 2) return;
            String target = parts[1];
            if (BUILTINS.contains(target)) {
                out.println(target + " is a shell builtin");
            } else {
                String pathEnv = System.getenv("PATH");
                String[] paths = pathEnv != null ? pathEnv.split(File.pathSeparator) : new String[0];
                boolean found = false;
                for (String path : paths) {
                    File file = new File(path, target);
                    if (file.exists() && file.canExecute()) {
                        out.println(target + " is " + file.getAbsolutePath());
                        found = true; break;
                    }
                }
                if (!found) out.println(target + ": not found");
            }

        } else if (command.equals("jobs")) {
            updateJobStatuses();
            int totalJobs = backgroundJobs.size();
            for (int i = 0; i < totalJobs; i++) {
                Job job = backgroundJobs.get(i);
                char marker = ' ';
                if (i == totalJobs - 1) marker = '+';
                else if (i == totalJobs - 2) marker = '-';
                out.printf("[%d]%c  %-24s%s%n", job.id, marker, job.status, job.command);
            }
            backgroundJobs.removeIf(job -> job.status.equals("Done"));
        }
        // exit and complete are not meaningful inside a pipeline
    }

    private static void executePipeline(List<String> segments) throws Exception {
        int n = segments.size();

        // Parse all segments upfront
        List<String[]> allParts = new ArrayList<>();
        for (String seg : segments) {
            allParts.add(parseCommand(seg));
        }

        // We chain processes/threads together using pipe streams.
        // For each adjacent pair we create a pipe: [PipedOutputStream -> PipedInputStream]
        // We use java.io.PipedInputStream/PipedOutputStream for builtin<->builtin or builtin<->process bridging.
        // For external<->external we let ProcessBuilder handle it via OS pipes.

        // Strategy: build a list of "runners" and connect them with byte pipes.
        // Each runner is either:
        //   - a Thread (for builtins) that reads from an InputStream and writes to an OutputStream
        //   - a Process (for external commands)

        // We'll use a simple approach: run everything in threads using piped streams.
        // External commands: start process with PIPE for stdin/stdout, bridge via threads.

        List<Thread> threads = new ArrayList<>();
        List<Process> processes = new ArrayList<>();

        // currentInput: the InputStream that the next stage should read from.
        // Starts as null (meaning inherit from terminal / System.in).
        InputStream currentInput = null;

        for (int i = 0; i < n; i++) {
            String[] parts = allParts.get(i);
            if (parts.length == 0) continue;

            boolean isLast = (i == n - 1);
            boolean isBuiltin = BUILTINS.contains(parts[0]);

            if (isBuiltin) {
                // Builtin: run in a thread
                // Output goes to: next stage's input pipe, or System.out if last
                final InputStream builtinInput = currentInput; // may be null (unused for most builtins)
                final String[] builtinParts = parts;

                if (isLast) {
                    // Write directly to System.out
                    final InputStream finalInput = currentInput;
                    Thread t = new Thread(() -> {
                        try {
                            runBuiltinToStream(builtinParts, finalInput, System.out);
                            System.out.flush();
                        } catch (Exception e) { /* ignore */ }
                    });
                    threads.add(t);
                    currentInput = null;
                } else {
                    // Pipe output to next stage
                    java.io.PipedOutputStream pipeOut = new java.io.PipedOutputStream();
                    java.io.PipedInputStream pipeIn = new java.io.PipedInputStream(pipeOut, 65536);
                    PrintStream ps = new PrintStream(pipeOut, true);

                    final InputStream finalInput = currentInput;
                    Thread t = new Thread(() -> {
                        try {
                            runBuiltinToStream(builtinParts, finalInput, ps);
                            ps.flush();
                            pipeOut.close();
                        } catch (Exception e) { /* ignore */ }
                    });
                    threads.add(t);
                    currentInput = pipeIn;
                }
            } else {
                // External command
                String execPath = resolveCommand(parts[0]);
                if (execPath == null) {
                    System.err.println(parts[0] + ": command not found");
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(Arrays.asList(parts));
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                if (currentInput == null) {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                }

                if (isLast) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }

                Process process = pb.start();
                processes.add(process);

                // If we have a builtin-produced input stream, pipe it into the process stdin
                if (currentInput != null) {
                    final InputStream src = currentInput;
                    final OutputStream dst = process.getOutputStream();
                    Thread piper = new Thread(() -> {
                        try (src; dst) {
                            byte[] buf = new byte[4096];
                            int read;
                            while ((read = src.read(buf)) != -1) {
                                dst.write(buf, 0, read);
                                dst.flush();
                            }
                        } catch (IOException e) { /* pipe closed */ }
                    });
                    piper.setDaemon(true);
                    threads.add(piper);
                }

                if (!isLast) {
                    // Next stage reads from this process's stdout
                    currentInput = process.getInputStream();
                } else {
                    currentInput = null;
                }
            }
        }

        // Start all threads
        for (Thread t : threads) t.start();

        // Wait for all threads and processes
        for (Thread t : threads) t.join();
        for (Process p : processes) p.waitFor();
    }

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
        } catch (Exception e) { /* ignore */ }
    }
}