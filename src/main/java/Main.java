import java.io.*;
import java.util.*;

import org.jline.reader.*;
import org.jline.reader.impl.*;
import org.jline.terminal.*;

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

        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        Completer completer = (LineReader reader,
                               ParsedLine line,
                               List<Candidate> candidates) -> {

            String word = line.word();

            if ("echo".startsWith(word)) {
                candidates.add(new Candidate("echo"));
            }

            if ("exit".startsWith(word)) {
                candidates.add(new Candidate("exit"));
            }
        };

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(new DefaultParser())
                .completer(completer)
                .build();

        while (true) {

            String input = reader.readLine("$ ");

            String[] parts = parseCommand(input);

            if (parts.length == 0) {
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
                    System.out.print(output);
                }

                if (stderrFile != null) {
                    new FileWriter(stderrFile, stderrAppend).close();
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

                        Process process = pb.start();
                        process.waitFor();

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
                        System.out.println(errorMessage);
                    }
                }
            }
        }
    }
}