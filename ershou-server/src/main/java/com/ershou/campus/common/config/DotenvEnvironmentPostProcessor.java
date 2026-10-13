package com.ershou.campus.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时自动读取项目根目录的 {@code .env}。
 *
 * <h3>为什么需要它（2026-10-02 踩的坑）</h3>
 * 高德地图的 key 放在 {@code .env} 里，而 {@code .env} 一直只有 {@code scripts/dev-server.ps1}
 * 会读进环境变量。于是：
 * <ul>
 *   <li>用脚本启动 → key 有，移动端地图正常；</li>
 *   <li><b>在 IDEA 里直接点 Run</b> → IDEA 不知道 {@code .env} 这回事，环境变量是空的
 *       → {@code ershou.amap.js-key} 解析成空串 → 移动端只显示"后端未配置高德 key"。</li>
 * </ul>
 * 症状就是用户说的"<b>每次重启都要重新配一个 key</b>"：其实配置一直都在，
 * 只是<b>换个启动方式就读不到</b>。靠"记得用某个脚本启动"来保证功能可用是不可靠的，
 * 所以这里把加载动作放进应用自己，与启动方式解耦。
 *
 * <h3>为什么用 EnvironmentPostProcessor</h3>
 * 它在 Environment 准备好之后、任何 {@code @Value} / 配置绑定之前执行，
 * 因此 {@code application.yml} 里的 {@code ${AMAP_JS_KEY:}} 占位符能正常解析。
 * 比 {@code application.yml} 里写 {@code spring.config.import} 更简单，
 * 也不用往 IDEA 的 Run Configuration 里手填环境变量。
 *
 * <h3>取值优先级</h3>
 * 用 {@code addFirst} 放在最前，因此 {@code .env} 的优先级**最高**：
 * 真实环境变量、命令行参数、IDE 里手填的值都会被它覆盖。
 * 这样做是为了"改一处就生效"（用户只需维护 {@code .env}）；
 * 副作用是没法再用环境变量临时覆盖单个配置项 —— 需要时请直接改 {@code .env}。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** {@code .env} 里这些变量是给构建/前端工具用的，注入后端 Environment 只会造成困惑 */
    private static final List<String> IGNORED_PREFIXES = List.of("VITE_");

    private static final String PROPERTY_SOURCE_NAME = "dotenvFile";

    /**
     * 候选路径：**不能只认一个**。
     *
     * <p>踩过的坑（2026-10-10）：最初只认"当前工作目录的上一级"（`../.env`）。
     * 从 `scripts/dev-server.ps1` 启动时工作目录是 {@code ershou-server}，能找到；
     * 但**从 IDEA 直接 Run 时找不到**（IDEA 的工作目录可以是项目根、也可以是别的），
     * 于是 key 又是空的、移动端地图又打不开 —— 表现和"根本没配 key"一模一样。
     * 所以按"最可能 → 次可能"列一串候选，逐个试到存在为止。
     */
    private static List<Path> candidateEnvFiles() {
        List<Path> candidates = new ArrayList<>();
        Path explicit = pathFromEnv("ERSHOU_ENV_FILE");
        if (explicit != null) {
            candidates.add(explicit);
        }
        Path root = pathFromEnv("ERSHOU_ROOT");
        if (root != null) {
            candidates.add(root.resolve(".env"));
        }
        Path base = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        // 工作目录 = 项目根 / ershou-server / 更深的子目录，三种都覆盖
        candidates.add(base.resolve(".env"));
        candidates.add(base.resolve("..").resolve(".env").normalize());
        candidates.add(base.resolve("..").resolve("..").resolve(".env").normalize());
        return candidates;
    }

    private static Path pathFromEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Paths.get(value.trim()).toAbsolutePath().normalize();
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> tried = new ArrayList<>();
        Map<String, Object> values = Map.of();
        Path found = null;
        for (Path candidate : candidateEnvFiles()) {
            tried.add(candidate.toString());
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            Map<String, Object> parsed = readDotenv(candidate);
            if (!parsed.isEmpty()) {
                values = parsed;
                found = candidate;
                break;
            }
        }

        if (found == null) {
            // 找不到就把"找过哪些路径"打出来 —— 否则用户只看到"未配置 key"，
            // 分不清是路径没对上还是文件里真的没写（这个坑排查过一次，别再让它难查）
            System.err.println("[dotenv] 没找到可用的 .env，已尝试：" + String.join("  |  ", tried));
            System.err.println("[dotenv] 可用环境变量 ERSHOU_ENV_FILE 直接指定 .env 的绝对路径");
            return;
        }

        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(PROPERTY_SOURCE_NAME)) {
            sources.remove(PROPERTY_SOURCE_NAME);
        }
        sources.addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
        System.out.println("[dotenv] 已从 " + found + " 载入 " + values.size() + " 个变量");
    }

    /** 解析 {@code .env}：忽略空行与整行注释，去掉可能的内联注释与 BOM */
    private Map<String, Object> readDotenv(Path file) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (rawLine == null) {
                    continue;
                }
                // 文件可能带 UTF-8 BOM
                String line = rawLine.replace("\uFEFF", "").trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // 内联注释：`KEY=value  # 说明`。注意值里本身不含 '#'（key 都是十六进制串）
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash).trim();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                // 去掉值两端可能的引号
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (IGNORED_PREFIXES.stream().anyMatch(key::startsWith)) {
                    continue;
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            System.err.println("[dotenv] 读取 " + file + " 失败：" + e.getMessage());
        }
        return values;
    }

    @Override
    public int getOrder() {
        // 尽量晚于其他 EnvironmentPostProcessor，确保我们是最后 addFirst 的那个
        return Ordered.LOWEST_PRECEDENCE;
    }
}
