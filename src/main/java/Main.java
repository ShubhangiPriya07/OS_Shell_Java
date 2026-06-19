import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
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
        String originalState = getSttyState();
        
        try {
            setRawMode();
            StringBuilder currentLine = new StringBuilder();
            
            System.out.print("$ ");
            System.out.flush();

            InputStream in = System.in;

            while (true) {
                int c = in.read();
                if (c == -1) break; 

                // 1. Handle TAB Autocompletion (ASCII 9)
                if (c == 9) {
                    String typed = currentLine.toString();
                    if (!typed.isEmpty()) {
                        if ("echo".startsWith(typed)) {
                            String completion = "echo".substring(typed.length()) + " ";
                            currentLine.append(completion);
                            System.out.print(completion);
                            System.out.flush();
                        } else if ("exit".startsWith(typed)) {
                            String completion = "exit".substring(typed.length()) + " ";
                            currentLine.append(completion);
                            System.out.print(completion);
                            System.out.flush();
                        }
                    }
                } 
                // 2. Handle Carriage Return / Line Feed (ASCII 13 or 10)
                else if (c == 10 || c == 13) {
                    System.out.print("\r\n");
                    System.out.flush();

                    String input = currentLine.toString();
                    currentLine.setLength(0); 

                    String[] parts = parseCommand(input);

                    if (parts.length == 0) {
                        System.out.print("$ ");
                        System.out.flush();
                        continue;
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
                        System.out.print("$ ");
                        System.out.flush();
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

                        if (stdoutFile != null) {
                            try (FileWriter writer =
                                         new FileWriter(stdoutFile, stdoutAppend)) {
                                writer.write(output.toString());
                            }
                        } else {
                            System.out.print(output.toString().replace("\n", "\r\n"));
                            System.out.flush();
                        }

                        if (stderrFile != null) {
                            new FileWriter(stderrFile, stderrAppend).close();
                        }
                    }

                    // --- ADDED: 'type' Builtin Command Handling ---
                    else if (command.equals("type")) {
                        if (parts.length > 1) {
                            String target = parts[1];
                            if (target.equals("echo") || target.equals("exit") || target.equals("type")) {
                                String msg = target + " is a shell builtin\r\n";
                                if (stdoutFile != null) {
                                    try (FileWriter writer = new FileWriter(stdoutFile, stdoutAppend)) {
                                        writer.write(target + " is a shell builtin\n");
                                    }
                                } else {
                                    System.out.print(msg);
                                    System.out.flush();
                                }
                            } else {
                                String pathEnv = System.getenv("PATH");
                                String[] paths = pathEnv.split(File.pathSeparator);
                                boolean pathFound = false;

                                for (String path : paths) {
                                    File file = new File(path, target);
                                    if (file.exists() && file.canExecute()) {
                                        String msg = target + " is " + file.getAbsolutePath() + "\r\n";
                                        if (stdoutFile != null) {
                                            try (FileWriter writer = new FileWriter(stdoutFile, stdoutAppend)) {
                                                writer.write(target + " is " + file.getAbsolutePath() + "\n");
                                            }
                                        } else {
                                            System.out.print(msg);
                                            System.out.flush();
                                        }
                                        pathFound = true;
                                        break;
                                    }
                                }

                                if (!pathFound) {
                                    String errMsg = target + ": not found\r\n";
                                    if (stderrFile != null) {
                                        try (FileWriter writer = new FileWriter(stderrFile, stderrAppend)) {
                                            writer.write(target + ": not found\n");
                                        }
                                    } else {
                                        System.out.print(errMsg);
                                        System.out.flush();
                                    }
                                }
                            }
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

                                if (stdoutFile != null) {
                                    if (stdoutAppend) {
                                        pb.redirectOutput(
                                                ProcessBuilder.Redirect.appendTo(
                                                        new File(stdoutFile)));
                                    } else {
                                        pb.redirectOutput(new File(stdoutFile));
                                    }
                                } else {
                                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                                }

                                if (stderrFile != null) {
                                    if (stderrAppend) {
                                        pb.redirectError(
                                                ProcessBuilder.Redirect.appendTo(
                                                        new File(stderrFile)));
                                    } else {
                                        pb.redirectError(new File(stderrFile));
                                    }
                                } else {
                                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                                }

                                restoreTerminal(originalState);
                                Process process = pb.start();
                                process.waitFor();
                                setRawMode(); 

                                found = true;
                                break;
                            }
                        }

                        if (!found) {
                            String errorMessage = command + ": command not found";

                            if (stderrFile != null) {
                                try (FileWriter writer =
                                             new FileWriter(stderrFile, stderrAppend)) {
                                    writer.write(errorMessage + System.lineSeparator());
                                }
                            } else {
                                System.out.print(errorMessage + "\r\n");
                                System.out.flush();
                            }
                        }
                    }

                    System.out.print("$ ");
                    System.out.flush();
                } 
                // 3. Handle Backspace (ASCII 127 or 8)
                else if (c == 127 || c == 8) {
                    if (currentLine.length() > 0) {
                        currentLine.deleteCharAt(currentLine.length() - 1);
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                } 
                // 4. Handle regular printable input characters
                else if (c >= 32 && c <= 126) {
                    currentLine.append((char) c);
                    System.out.print((char) c);
                    System.out.flush();
                }
            }
        } finally {
            restoreTerminal(originalState);
        }
    }

    // --- Native Terminal Controls ---

    private static String getSttyState() {
        try {
            return runCommand(new String[]{"/bin/sh", "-c", "stty -g < /dev/tty"}).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void setRawMode() {
        try {
            runCommand(new String[]{"/bin/sh", "-c", "stty raw -echo < /dev/tty"});
        } catch (Exception e) {
            try {
                runCommand(new String[]{"/bin/sh", "-c", "stty raw -echo"});
            } catch (Exception ignored) {}
        }
    }

    private static void restoreTerminal(String originalState) {
        try {
            if (!originalState.isEmpty()) {
                runCommand(new String[]{"/bin/sh", "-c", "stty " + originalState + " < /dev/tty"});
            } else {
                runCommand(new String[]{"/bin/sh", "-c", "stty sane < /dev/tty"});
            }
        } catch (Exception e) {
            try {
                runCommand(new String[]{"/bin/sh", "-c", "stty sane"});
            } catch (Exception ignored) {}
        }
    }

    private static String runCommand(String[] cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).start();
        p.waitFor();
        return new String(p.getInputStream().readAllBytes());
    }
}