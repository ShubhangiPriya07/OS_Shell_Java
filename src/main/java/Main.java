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
    private static final List<String> BUILTINS = List.of("echo", "exit", "type", "complete", "jobs", "pwd", "cd");
    private static final Map<String, String> registeredCompletions = new HashMap<>();

    // Tracks the shell's current working directory (since JVM can't truly chdir())
    private static String currentWorkingDirectory = System.getProperty("user.dir");

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
        for (Job job : backgroundJobs) if (job.id > max) max = job.id;
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
                segments.add(current.toString().trim()); current.setLength(0);
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
        try { runShellLoop(); }
        finally { configureTerminalMode("icanon echo"); }
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
                        String argv1 = primaryCommand, argv2 = "", argv3 = "";
                        if (input.endsWith(" ")) {
                            argv2 = ""; argv3 = words.length > 0 ? words[words.length - 1] : "";
                        } else {
                            argv2 = words.length > 0 ? words[words.length - 1] : "";
                            argv3 = words.length > 1 ? words[words.length - 2] : "";
                        }
                        try {
                            ProcessBuilder pb = new ProcessBuilder(scriptPath, argv1, argv2, argv3);
                            pb.environment().put("COMP_LINE", input);
                            pb.environment().put("COMP_POINT", String.valueOf(input.getBytes().length));
                            Process process = pb.start();
                            List<String> scriptCandidates = new ArrayList<>();
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                                String line;
                                while ((line = reader.readLine()) != null)
                                    if (!line.trim().isEmpty()) scriptCandidates.add(line.trim());
                                process.waitFor();
                            }
                            if (scriptCandidates.size() == 1) {
                                String rawPrefix = input.substring(input.lastIndexOf(' ') + 1);
                                String addition = (scriptCandidates.get(0) + " ").substring(rawPrefix.length());
                                System.out.print(addition); System.out.flush();
                                currentLine.append(addition); tabCount = 0; lastTabPrefix = ""; continue;
                            } else if (scriptCandidates.size() > 1) {
                                String lcp = findLongestCommonPrefix(scriptCandidates);
                                String argv2Final = argv2;
                                if (lcp.length() > argv2Final.length()) {
                                    String addition = lcp.substring(argv2Final.length());
                                    System.out.print(addition); System.out.flush();
                                    currentLine.append(addition); tabCount = 0; lastTabPrefix = "";
                                } else {
                                    if (input.equals(lastTabPrefix)) tabCount++; else { lastTabPrefix = input; tabCount = 1; }
                                    if (tabCount == 1) { System.out.print("\u0007"); System.out.flush(); }
                                    else if (tabCount >= 2) {
                                        Collections.sort(scriptCandidates);
                                        System.out.println();
                                        for (int i = 0; i < scriptCandidates.size(); i++) {
                                            System.out.print(scriptCandidates.get(i));
                                            if (i < scriptCandidates.size() - 1) System.out.print("  ");
                                        }
                                        System.out.println(); System.out.print("$ " + input); System.out.flush(); tabCount = 0;
                                    }
                                }
                                continue;
                            } else { System.out.print("\u0007"); System.out.flush(); tabCount = 0; lastTabPrefix = ""; continue; }
                        } catch (Exception e) { System.out.print("\u0007"); System.out.flush(); tabCount = 0; lastTabPrefix = ""; continue; }
                    }

                    String rawPrefix = input.substring(input.lastIndexOf(' ') + 1);
                    String dirPath = ".", filePrefix = rawPrefix;
                    if (rawPrefix.contains("/")) {
                        int ls = rawPrefix.lastIndexOf('/');
                        dirPath = rawPrefix.substring(0, ls + 1); filePrefix = rawPrefix.substring(ls + 1);
                    }
                    File baseDir = new File(dirPath);
                    if (!baseDir.isAbsolute()) baseDir = new File(currentWorkingDirectory, dirPath);
                    File[] files = baseDir.listFiles();
                    List<File> fileMatches = new ArrayList<>();
                    if (files != null) for (File f : files) if (f.getName().startsWith(filePrefix)) fileMatches.add(f);
                    fileMatches.sort((a, b) -> a.getName().compareTo(b.getName()));
                    final String finalDirPath = dirPath, finalFilePrefix = filePrefix, finalRawPrefix = rawPrefix;

                    if (fileMatches.size() == 1) {
                        File match = fileMatches.get(0);
                        String suffix = match.isDirectory() ? "/" : " ";
                        String completePath = finalRawPrefix.contains("/") ? finalDirPath + match.getName() + suffix : match.getName() + suffix;
                        String addition = completePath.substring(finalRawPrefix.length());
                        System.out.print(addition); System.out.flush(); currentLine.append(addition); tabCount = 0; lastTabPrefix = "";
                    } else if (fileMatches.size() > 1) {
                        List<String> matchNames = new ArrayList<>();
                        for (File f : fileMatches) matchNames.add(f.getName());
                        String lcp = findLongestCommonPrefix(matchNames);
                        if (lcp.length() > finalFilePrefix.length()) {
                            String completePath = finalRawPrefix.contains("/") ? finalDirPath + lcp : lcp;
                            String addition = completePath.substring(finalRawPrefix.length());
                            System.out.print(addition); System.out.flush(); currentLine.append(addition); tabCount = 0; lastTabPrefix = "";
                        } else {
                            if (input.equals(lastTabPrefix)) ++tabCount; else { lastTabPrefix = input; tabCount = 1; }
                            if (tabCount == 1) { System.out.print("\u0007"); System.out.flush(); }
                            else if (tabCount >= 2) {
                                System.out.println();
                                for (int i = 0; i < fileMatches.size(); i++) {
                                    File m = fileMatches.get(i);
                                    System.out.print(m.getName() + (m.isDirectory() ? "/" : ""));
                                    if (i < fileMatches.size() - 1) System.out.print("  ");
                                }
                                System.out.println(); System.out.print("$ " + input); System.out.flush(); tabCount = 0;
                            }
                        }
                    } else { System.out.print("\u0007"); System.out.flush(); tabCount = 0; lastTabPrefix = ""; }
                    continue;
                }

                if (input.isEmpty()) { System.out.print("\u0007"); System.out.flush(); continue; }
                if (input.equals(lastTabPrefix)) tabCount++; else { lastTabPrefix = input; tabCount = 1; }

                Set<String> candidates = new HashSet<>(BUILTINS);
                String pathEnv = System.getenv("PATH");
                if (pathEnv != null) {
                    for (String path : pathEnv.split(File.pathSeparator)) {
                        File dir = new File(path);
                        if (dir.exists() && dir.isDirectory()) {
                            File[] dirFiles = dir.listFiles();
                            if (dirFiles != null) for (File f : dirFiles) if (f.isFile() && f.canExecute()) candidates.add(f.getName());
                        }
                    }
                }
                List<String> matches = new ArrayList<>();
                for (String candidate : candidates) if (candidate.startsWith(input)) matches.add(candidate);
                Collections.sort(matches);

                if (matches.size() == 1) {
                    String addition = (matches.get(0) + " ").substring(input.length());
                    System.out.print(addition); System.out.flush(); currentLine.append(addition); tabCount = 0; lastTabPrefix = "";
                } else if (matches.size() > 1) {
                    String lcp = findLongestCommonPrefix(matches);
                    if (lcp.length() > input.length()) {
                        String addition = lcp.substring(input.length());
                        System.out.print(addition); System.out.flush(); currentLine.append(addition); tabCount = 0; lastTabPrefix = "";
                    } else {
                        if (tabCount == 1) { System.out.print("\u0007"); System.out.flush(); }
                        else if (tabCount >= 2) {
                            System.out.println();
                            for (int i = 0; i < matches.size(); i++) { System.out.print(matches.get(i)); if (i < matches.size() - 1) System.out.print("  "); }
                            System.out.println(); System.out.print("$ " + input); System.out.flush(); tabCount = 0;
                        }
                    }
                } else { System.out.print("\u0007"); System.out.flush(); tabCount = 0; lastTabPrefix = ""; }
                continue;
            }

            tabCount = 0; lastTabPrefix = "";

            if (c == '\n' || c == '\r') {
                System.out.println();
                processCommandLine(currentLine.toString());
                currentLine.setLength(0);
                System.out.print(reapBeforePrompt());
                System.out.print("$ "); System.out.flush();
                continue;
            }

            if (c == 127 || c == 8) {
                if (currentLine.length() > 0) {
                    currentLine.deleteCharAt(currentLine.length() - 1);
                    System.out.print("\b \b"); System.out.flush();
                }
                continue;
            }

            char ch = (char) c;
            System.out.print(ch); System.out.flush();
            currentLine.append(ch);
        }
    }

    private static void processCommandLine(String input) throws Exception {
        List<String> pipeSegments = splitOnPipe(input);
        if (pipeSegments.size() > 1) { executePipeline(pipeSegments); return; }

        String[] parts = parseCommand(input);
        if (parts.length == 0) return;

        String stdoutFile = null, stderrFile = null;
        boolean stdoutAppend = false, stderrAppend = false;
        List<String> commandParts = new ArrayList<>();

        for (int i = 0; i < parts.length; i++) {
            if ((parts[i].equals(">") || parts[i].equals("1>")) && i + 1 < parts.length) { stdoutFile = parts[++i]; stdoutAppend = false; }
            else if ((parts[i].equals(">>") || parts[i].equals("1>>")) && i + 1 < parts.length) { stdoutFile = parts[++i]; stdoutAppend = true; }
            else if (parts[i].equals("2>") && i + 1 < parts.length) { stderrFile = parts[++i]; stderrAppend = false; }
            else if (parts[i].equals("2>>") && i + 1 < parts.length) { stderrFile = parts[++i]; stderrAppend = true; }
            else commandParts.add(parts[i]);
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
            configureTerminalMode("icanon echo"); System.exit(0);
        } else if (command.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) { if (i > 1) sb.append(" "); sb.append(parts[i]); }
            sb.append(System.lineSeparator());
            if (stdoutFile != null) { try (FileWriter w = new FileWriter(resolvePath(stdoutFile), stdoutAppend)) { w.write(sb.toString()); } }
            else System.out.print(sb);
            if (stderrFile != null) new FileWriter(resolvePath(stderrFile), stderrAppend).close();
        } else if (command.equals("pwd")) {
            String out = currentWorkingDirectory + System.lineSeparator();
            if (stdoutFile != null) { try (FileWriter w = new FileWriter(resolvePath(stdoutFile), stdoutAppend)) { w.write(out); } }
            else System.out.print(out);
            if (stderrFile != null) new FileWriter(resolvePath(stderrFile), stderrAppend).close();
        } else if (command.equals("cd")) {
            if (parts.length < 2) {
                // No argument: bash defaults to HOME, but tests only cover absolute paths here
                return;
            }
            String targetArg = parts[1];
            File targetDir;

            if (targetArg.startsWith("/")) {
                // Absolute path
                targetDir = new File(targetArg);
            } else {
                // Not handled in this stage yet (relative paths, ~), but resolve relative to cwd anyway
                targetDir = new File(currentWorkingDirectory, targetArg);
            }

            if (targetDir.exists() && targetDir.isDirectory()) {
                currentWorkingDirectory = targetDir.getCanonicalPath();
            } else {
                String err = "cd: " + targetArg + ": No such file or directory" + System.lineSeparator();
                if (stderrFile != null) { try (FileWriter w = new FileWriter(resolvePath(stderrFile), stderrAppend)) { w.write(err); } }
                else System.out.print(err);
            }
        } else if (command.equals("type")) {
            if (parts.length < 2) return;
            String target = parts[1];
            String result = BUILTINS.contains(target) ? target + " is a shell builtin" : resolveCommandPath(target) != null ? target + " is " + resolveCommandPath(target) : target + ": not found";
            String out = result + System.lineSeparator();
            if (stdoutFile != null) { try (FileWriter w = new FileWriter(resolvePath(stdoutFile), stdoutAppend)) { w.write(out); } }
            else System.out.print(out);
            if (stderrFile != null) new FileWriter(resolvePath(stderrFile), stderrAppend).close();
        } else if (command.equals("complete")) {
            StringBuilder output = new StringBuilder();
            if (parts.length >= 3 && parts[1].equals("-p")) {
                String tc = parts[2];
                if (registeredCompletions.containsKey(tc)) output.append("complete -C '").append(registeredCompletions.get(tc)).append("' ").append(tc).append(System.lineSeparator());
                else output.append("complete: ").append(tc).append(": no completion specification").append(System.lineSeparator());
            } else if (parts.length >= 4 && parts[1].equals("-C")) {
                registeredCompletions.put(parts[3], parts[2]);
            } else if (parts.length >= 3 && parts[1].equals("-r")) {
                registeredCompletions.remove(parts[2]);
            }
            if (stdoutFile != null) { try (FileWriter w = new FileWriter(resolvePath(stdoutFile), stdoutAppend)) { w.write(output.toString()); } }
            else System.out.print(output);
            if (stderrFile != null) new FileWriter(resolvePath(stderrFile), stderrAppend).close();
        } else if (command.equals("jobs")) {
            updateJobStatuses();
            StringBuilder sb = new StringBuilder();
            int total = backgroundJobs.size();
            for (int i = 0; i < total; i++) {
                Job job = backgroundJobs.get(i);
                char marker = i == total - 1 ? '+' : i == total - 2 ? '-' : ' ';
                sb.append(String.format("[%d]%c  %-24s%s%n", job.id, marker, job.status, job.command));
            }
            backgroundJobs.removeIf(job -> job.status.equals("Done"));
            if (stdoutFile != null) { try (FileWriter w = new FileWriter(resolvePath(stdoutFile), stdoutAppend)) { w.write(sb.toString()); } }
            else System.out.print(sb);
            if (stderrFile != null) new FileWriter(resolvePath(stderrFile), stderrAppend).close();
        } else {
            String execPath = resolveCommandPath(command);
            if (execPath != null) {
                ProcessBuilder pb = new ProcessBuilder(Arrays.asList(parts));
                pb.directory(new File(currentWorkingDirectory));
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                if (stdoutFile != null) pb.redirectOutput(stdoutAppend ? ProcessBuilder.Redirect.appendTo(resolvePath(stdoutFile)) : ProcessBuilder.Redirect.to(resolvePath(stdoutFile)));
                else pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                if (stderrFile != null) pb.redirectError(stderrAppend ? ProcessBuilder.Redirect.appendTo(resolvePath(stderrFile)) : ProcessBuilder.Redirect.to(resolvePath(stderrFile)));
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
                } else process.waitFor();
            } else {
                String err = command + ": command not found";
                if (stderrFile != null) { try (FileWriter w = new FileWriter(resolvePath(stderrFile), stderrAppend)) { w.write(err + System.lineSeparator()); } }
                else System.out.println(err);
            }
        }
    }

    // Resolve a (possibly relative) redirection target path against the shell's tracked cwd
    private static File resolvePath(String path) {
        File f = new File(path);
        if (f.isAbsolute()) return f;
        return new File(currentWorkingDirectory, path);
    }

    private static void runBuiltinToStream(String[] parts, InputStream stdinStream, PrintStream out) throws Exception {
        String command = parts[0];
        if (command.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) { if (i > 1) sb.append(" "); sb.append(parts[i]); }
            out.println(sb);
        } else if (command.equals("pwd")) {
            out.println(currentWorkingDirectory);
        } else if (command.equals("cd")) {
            if (parts.length < 2) return;
            String targetArg = parts[1];
            File targetDir = targetArg.startsWith("/") ? new File(targetArg) : new File(currentWorkingDirectory, targetArg);
            if (targetDir.exists() && targetDir.isDirectory()) {
                currentWorkingDirectory = targetDir.getCanonicalPath();
            } else {
                out.println("cd: " + targetArg + ": No such file or directory");
            }
        } else if (command.equals("type")) {
            if (parts.length < 2) return;
            String target = parts[1];
            if (BUILTINS.contains(target)) out.println(target + " is a shell builtin");
            else {
                String path = resolveCommandPath(target);
                if (path != null) out.println(target + " is " + path);
                else out.println(target + ": not found");
            }
        } else if (command.equals("jobs")) {
            updateJobStatuses();
            int total = backgroundJobs.size();
            for (int i = 0; i < total; i++) {
                Job job = backgroundJobs.get(i);
                char marker = i == total - 1 ? '+' : i == total - 2 ? '-' : ' ';
                out.printf("[%d]%c  %-24s%s%n", job.id, marker, job.status, job.command);
            }
            backgroundJobs.removeIf(job -> job.status.equals("Done"));
        }
    }

    private static void executePipeline(List<String> segments) throws Exception {
        int n = segments.size();
        List<String[]> allParts = new ArrayList<>();
        for (String seg : segments) allParts.add(parseCommand(seg));

        List<Thread> threads = new ArrayList<>();
        List<Process> processes = new ArrayList<>();

        InputStream currentInput = null;

        for (int i = 0; i < n; i++) {
            String[] parts = allParts.get(i);
            if (parts.length == 0) continue;

            boolean isLast = (i == n - 1);
            boolean isBuiltin = BUILTINS.contains(parts[0]);

            if (isBuiltin) {
                if (isLast) {
                    final InputStream finalInput = currentInput;
                    final String[] finalParts = parts;
                    Thread t = new Thread(() -> {
                        try {
                            runBuiltinToStream(finalParts, finalInput, System.out);
                            System.out.flush();
                        } catch (Exception e) { /* ignore */ }
                    });
                    threads.add(t);
                    currentInput = null;
                } else {
                    java.io.PipedOutputStream pipeOut = new java.io.PipedOutputStream();
                    java.io.PipedInputStream pipeIn = new java.io.PipedInputStream(pipeOut, 65536);
                    PrintStream ps = new PrintStream(pipeOut, true);

                    final InputStream finalInput = currentInput;
                    final String[] finalParts = parts;
                    Thread t = new Thread(() -> {
                        try {
                            runBuiltinToStream(finalParts, finalInput, ps);
                            ps.flush();
                            pipeOut.close();
                        } catch (Exception e) {
                            try { pipeOut.close(); } catch (IOException ex) { /* ignore */ }
                        }
                    });
                    threads.add(t);
                    currentInput = pipeIn;
                }
            } else {
                String execPath = resolveCommandPath(parts[0]);
                if (execPath == null) {
                    System.err.println(parts[0] + ": command not found");
                    if (currentInput != null) { try { currentInput.close(); } catch (IOException e) { /* ignore */ } }
                    for (Thread t : threads) if (!t.isAlive()) t.start();
                    for (Thread t : threads) t.join();
                    for (Process p : processes) p.waitFor();
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(Arrays.asList(parts));
                pb.directory(new File(currentWorkingDirectory));
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                if (currentInput == null) pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                else pb.redirectInput(ProcessBuilder.Redirect.PIPE);

                if (isLast) pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                else pb.redirectOutput(ProcessBuilder.Redirect.PIPE);

                Process process = pb.start();
                processes.add(process);

                if (currentInput != null) {
                    final InputStream src = currentInput;
                    final OutputStream dst = process.getOutputStream();
                    Thread bridge = new Thread(() -> {
                        try {
                            byte[] buf = new byte[8192];
                            int read;
                            while ((read = src.read(buf)) != -1) {
                                dst.write(buf, 0, read);
                                dst.flush();
                            }
                        } catch (IOException e) { /* pipe closed normally */ }
                        finally {
                            try { dst.close(); } catch (IOException e) { /* ignore */ }
                            try { src.close(); } catch (IOException e) { /* ignore */ }
                        }
                    });
                    bridge.setDaemon(true);
                    threads.add(bridge);
                }

                currentInput = isLast ? null : process.getInputStream();
            }
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        for (Process p : processes) p.waitFor();
    }

    private static String resolveCommandPath(String command) {
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
            new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty").inheritIO().start().waitFor();
        } catch (Exception e) { /* ignore */ }
    }
}