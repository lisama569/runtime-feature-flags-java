package com.github.lisama569.runtimefeatureflagsjava.source;

import com.github.lisama569.runtimefeatureflagsjava.model.RejectionKind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 基于本地 properties 文件的配置来源。每次 {@link #load()} 都重新读盘，
 * 因此天然支持热更新，无需文件监听。
 *
 * <p>拒绝原因区分：</p>
 * <ul>
 *   <li>文件不存在 → {@link RejectionKind#SOURCE_MISSING}；</li>
 *   <li>文件存在但无法读取或内容无法解析 → {@link RejectionKind#SOURCE_UNREADABLE}。</li>
 * </ul>
 * 本层不做任何取值校验——非法取值（类型错误、越界等）由合并阶段给出
 * {@link RejectionKind#INVALID_VALUE}，三类原因互不混淆。
 */
public class PropertiesFileConfigSource implements ConfigSource {

    private final String name;
    private final Path path;
    private final boolean required;

    public PropertiesFileConfigSource(String name, Path path, boolean required) {
        this.name = name;
        this.path = path;
        this.required = required;
    }

    @Override
    public String name() {
        return name;
    }

    public Path getPath() {
        return path;
    }

    /** 是否为必需来源：必需来源缺失属于硬拒绝，可选来源缺失只记录不阻断。 */
    public boolean isRequired() {
        return required;
    }

    @Override
    public RawSourceData load() throws SourceLoadException {
        if (!Files.exists(path)) {
            throw new SourceLoadException(RejectionKind.SOURCE_MISSING,
                    "source '" + name + "' file not found: " + path);
        }
        if (Files.isDirectory(path) || !Files.isReadable(path)) {
            throw new SourceLoadException(RejectionKind.SOURCE_MISSING,
                    "source '" + name + "' is not a readable regular file: " + path);
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        } catch (IOException e) {
            throw new SourceLoadException(RejectionKind.SOURCE_UNREADABLE,
                    "source '" + name + "' cannot be read: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            // Properties.load 在遇到畸形 Unicode 转义时抛出
            throw new SourceLoadException(RejectionKind.SOURCE_UNREADABLE,
                    "source '" + name + "' contains malformed content: " + e.getMessage(), e);
        }
        return new RawSourceData(name, properties);
    }
}
