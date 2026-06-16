import java.io.File;
import java.util.*;

public class Main {

    private static String[] parseCommand(String input) {
        List<String> args = new ArrayList<>();

        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\'') {
                inSingleQuotes = !inSingleQuotes;
            } else if (Character.isWhitespace(c) && !inSingleQuotes) {
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

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String input = sc.nextLine();

            String[] parts = parseCommand(input);

            if (parts.length == 0) {
                continue;
            }

            String command = parts[0];

            if (command.equals("exit")) {
                break;
            }

            else if (command.equals("echo")) {
                for (int i = 1; i < parts.length; i++) {
                    if (i > 1) {
                        System.out.print(" ");
                    }
                    System.out.print(parts[i]);
                }
                System.out.println();
            }

            else if (command.equals("type")) {
                if (parts.length < 2) {
                    continue;
                }

                String cmd = parts[1];

                if (cmd.equals("echo") ||
                    cmd.equals("exit") ||
                    cmd.equals("type")) {

                    System.out.println(cmd + " is a shell builtin");
                } else {

                    String pathEnv = System.getenv("PATH");
                    String[] paths = pathEnv.split(File.pathSeparator);

                    boolean found = false;

                    for (String path : paths) {
                        File file = new File(path, cmd);

                        if (file.exists() && file.canExecute()) {
                            System.out.println(cmd + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        System.out.println(cmd + ": not found");
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
                        ProcessBuilder pb = new ProcessBuilder(Arrays.asList(parts));
                        pb.inheritIO();

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