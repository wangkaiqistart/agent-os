package com.agentos.cli;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * init 专用的离线模型解析器，不装配 Spring，也不读取 Provider/数据库连接。
 * 已提供的高优先级值即使无效也不回退，避免悄悄用另一个模型生成 Agent。
 */
public final class InitModelResolver {
    private static final String MODEL_KEY = "AGENTOS_LLM_MODEL";
    private final Function<String, String> environment;
    private final Path directory;

    public InitModelResolver(Function<String, String> environment, Path directory) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** 只在 main 缺失时调用；null 表示未提供，空字符串属于无效的显式输入。 */
    public DefaultAgentOptions resolve(String model, String provider) throws IOException {
        if (model != null) {
            return new DefaultAgentOptions(model, provider);
        }
        String processModel = environment.apply(MODEL_KEY);
        if (processModel != null) {
            return new DefaultAgentOptions(processModel, provider);
        }
        return new DefaultAgentOptions(fileModel(), provider);
    }

    private String fileModel() throws IOException {
        Path file = directory.resolve(".env");
        if (Files.notExists(file)) {
            return null;
        }
        // 让 JDK 处理 UTF-8 Reader 的 properties 转义/续行语法；put 仅保留白名单键，
        // 其他配置值不会保存在结果对象中。这里不执行 shell，不展开变量或去引号。
        Properties projection = new ModelProjection();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            projection.load(reader);
            return projection.getProperty(MODEL_KEY);
        } catch (IllegalArgumentException e) {
            // 原异常可能携带配置文本，既不拼接也不保留为对外异常的 cause。
            throw new IllegalArgumentException(".env 配置语法不合法，请检查 AGENTOS_LLM_MODEL");
        } catch (IOException e) {
            throw new IOException("无法读取执行目录的 .env，请检查文件权限与类型");
        }
    }

    /** 解析器会经过整份语法，但只存储模型名；这不是通用配置加载器。 */
    private static final class ModelProjection extends Properties {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Object put(Object key, Object value) {
            return MODEL_KEY.equals(key) ? super.put(key, value) : null;
        }
    }
}
