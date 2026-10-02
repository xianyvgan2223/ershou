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

    /** {@code .env} 在项目根目录，即后端模块的上一级 */
    private static final Path ENV_FILE = Paths.get("..", ".env").toAbsolutePath().normalize();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> values = readDotenv(ENV_FILE);
        if (values.isEmpty()) {
            // 用 System.err 而不是 logger：这一步可能早于日志系统初始化
            System.err.println("[dotenv] 未读到 " + ENV_FILE + "（跳过；若依赖 .env 里的 key，请确认文件存在）");
            return;
        }
        MutablePropertySources sources = environment.getPropertySources();
        // 允许显式关闭（例如部署时确实想只用真实环境变量）
        if (sources.contains(PROPERTY_SOURCE_NAME)) {
            sources.remove(PROPERTY_SOURCE_NAME);
        }
        sources.addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
        System.out.println("[dotenv] 已从 " + ENV_FILE + " 载入 " + values.size() + " 个变量");
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
