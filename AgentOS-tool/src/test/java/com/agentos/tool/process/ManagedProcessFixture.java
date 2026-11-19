package com.agentos.tool.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** 仅测试进程入口；无需shell解释器，不输出宿主环境。 */
public final class ManagedProcessFixture {
    private ManagedProcessFixture() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "argv" ->
                    System.out.print(
                            JsonMapper.builder()
                                    .build()
                                    .writeValueAsString(List.of(args).subList(1, args.length)));
            case "env" ->
                    System.out.print(
                            JsonMapper.builder().build().writeValueAsString(System.getenv()));
            case "exit" -> {
                System.err.print("expected-error");
                System.exit(7);
            }
            case "flood" -> {
                var out =
                        Thread.ofPlatform()
                                .start(() -> {
                                    for (int i = 0; i < 40000; i++) {
                                        System.out.print("输出");
                                    }
                                });
                for (int i = 0; i < 40000; i++) {
                    System.err.print("错误");
                }
                out.join();
            }
            case "tree" -> {
                var javaExecutable =
                        Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
                var child =
                        new ProcessBuilder(
                                        javaExecutable.toString(),
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        ManagedProcessFixture.class.getName(),
                                        "sleep")
                                .start();
                var marker = Path.of(args[1]);
                var temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
                Files.writeString(temporary, Long.toString(child.pid()));
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                child.waitFor();
            }
            case "sleep" -> Thread.sleep(60000);
            case "action" -> Files.writeString(Path.of(args[1]), "executed");
            default -> throw new IllegalArgumentException("未知测试模式");
        }
    }
}
