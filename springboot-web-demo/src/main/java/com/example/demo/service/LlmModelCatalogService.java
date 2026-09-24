package com.example.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmModelCatalogService {
    private final JdbcTemplate jdbc;

    @Value("${agent-service.provider:deepseek}")
    private String defaultProvider;

    @Value("${agent-service.model:deepseek-chat}")
    private String defaultModel;

    public LlmModelCatalogService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void ensureSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS llm_model_config ("
                + "id VARCHAR(80) PRIMARY KEY,provider VARCHAR(40) NOT NULL,model_name VARCHAR(120) NOT NULL,"
                + "display_name VARCHAR(120) NOT NULL,enabled TINYINT(1) NOT NULL DEFAULT 1,"
                + "user_selectable TINYINT(1) NOT NULL DEFAULT 1,is_default TINYINT(1) NOT NULL DEFAULT 0,"
                + "input_price_per_million DECIMAL(14,6) NOT NULL DEFAULT 0,"
                + "output_price_per_million DECIMAL(14,6) NOT NULL DEFAULT 0,sort_order INT NOT NULL DEFAULT 100,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "UNIQUE KEY uk_llm_provider_model(provider,model_name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("assistant_conversation", "selected_model_id", "VARCHAR(80) NULL");
        addColumnIfMissing("prompt_online_metric", "provider", "VARCHAR(40) NOT NULL DEFAULT 'none'");
        addColumnIfMissing("prompt_online_metric", "model", "VARCHAR(120) NOT NULL DEFAULT 'none'");
        addColumnIfMissing("prompt_online_metric", "input_unit_price", "DECIMAL(14,6) NOT NULL DEFAULT 0");
        addColumnIfMissing("prompt_online_metric", "output_unit_price", "DECIMAL(14,6) NOT NULL DEFAULT 0");
        String provider = normalize(defaultProvider, "deepseek").toLowerCase();
        String model = normalize(defaultModel, "deepseek-chat");
        Integer modelCount = jdbc.queryForObject("SELECT COUNT(*) FROM llm_model_config", Integer.class);
        boolean firstModel = modelCount == null || modelCount == 0;
        double inputPrice = "deepseek".equals(provider) ? 3d : 0d;
        double outputPrice = "deepseek".equals(provider) ? 9d : 0d;
        jdbc.update("INSERT IGNORE INTO llm_model_config(id,provider,model_name,display_name,is_default,input_price_per_million,output_price_per_million,sort_order) "
                        + "VALUES(?,?,?,?,?,?,?,0)", modelId(provider, model), provider, model,
                displayName(provider, model), firstModel, inputPrice, outputPrice);
    }

    public List<Map<String, Object>> listSelectable() {
        return jdbc.query("SELECT id,provider,model_name,display_name,is_default,input_price_per_million,output_price_per_million "
                        + "FROM llm_model_config WHERE enabled=1 AND user_selectable=1 ORDER BY is_default DESC,sort_order,id",
                (rs, row) -> toMap(new ModelConfig(rs.getString("id"), rs.getString("provider"),
                        rs.getString("model_name"), rs.getString("display_name"), rs.getBoolean("is_default"),
                        rs.getDouble("input_price_per_million"), rs.getDouble("output_price_per_million"))));
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.query("SELECT id,provider,model_name,display_name,enabled,user_selectable,is_default,input_price_per_million,output_price_per_million,sort_order "
                        + "FROM llm_model_config ORDER BY is_default DESC,sort_order,id", (rs, row) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", rs.getString("id")); result.put("provider", rs.getString("provider"));
            result.put("model", rs.getString("model_name")); result.put("displayName", rs.getString("display_name"));
            result.put("enabled", rs.getBoolean("enabled")); result.put("userSelectable", rs.getBoolean("user_selectable"));
            result.put("default", rs.getBoolean("is_default")); result.put("inputPricePerMillion", rs.getDouble("input_price_per_million"));
            result.put("outputPricePerMillion", rs.getDouble("output_price_per_million")); result.put("sortOrder", rs.getInt("sort_order"));
            return result;
        });
    }

    @Transactional
    public Map<String, Object> save(String id, Map<String, Object> payload) {
        String provider = required(payload.get("provider"), "Provider").toLowerCase();
        String model = required(payload.get("model"), "模型名称");
        String safeId = id == null || id.trim().isEmpty() ? modelId(provider, model) : id.trim();
        if (!safeId.matches("[a-zA-Z0-9._-]{1,80}")) throw new IllegalArgumentException("模型 ID 只能包含字母、数字、点、横线和下划线");
        String display = text(payload.get("displayName"));
        if (display.isEmpty()) display = displayName(provider, model);
        boolean enabled = bool(payload.get("enabled"), true);
        boolean selectable = bool(payload.get("userSelectable"), true);
        boolean isDefault = bool(payload.get("default"), false);
        if (isDefault) jdbc.update("UPDATE llm_model_config SET is_default=0");
        jdbc.update("INSERT INTO llm_model_config(id,provider,model_name,display_name,enabled,user_selectable,is_default,input_price_per_million,output_price_per_million,sort_order) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE provider=VALUES(provider),model_name=VALUES(model_name),display_name=VALUES(display_name),"
                        + "enabled=VALUES(enabled),user_selectable=VALUES(user_selectable),is_default=VALUES(is_default),input_price_per_million=VALUES(input_price_per_million),"
                        + "output_price_per_million=VALUES(output_price_per_million),sort_order=VALUES(sort_order)",
                safeId, provider, model, display, enabled, selectable, isDefault,
                number(payload.get("inputPricePerMillion")), number(payload.get("outputPricePerMillion")), integer(payload.get("sortOrder"), 100));
        return listAll().stream().filter(item -> safeId.equals(item.get("id"))).findFirst().orElseThrow(IllegalStateException::new);
    }

    public void disable(String id) {
        ModelConfig model = resolve(id);
        if (model.isDefault()) throw new IllegalArgumentException("默认模型不能停用，请先设置另一个默认模型");
        jdbc.update("UPDATE llm_model_config SET enabled=0,user_selectable=0 WHERE id=?", id);
    }

    public ModelConfig resolve(String id) {
        List<ModelConfig> rows = id == null || id.trim().isEmpty()
                ? jdbc.query("SELECT id,provider,model_name,display_name,is_default,input_price_per_million,output_price_per_million "
                                + "FROM llm_model_config WHERE enabled=1 AND is_default=1 ORDER BY sort_order LIMIT 1", this::map)
                : jdbc.query("SELECT id,provider,model_name,display_name,is_default,input_price_per_million,output_price_per_million "
                                + "FROM llm_model_config WHERE enabled=1 AND id=?", this::map, id.trim());
        if (rows.isEmpty()) throw new IllegalArgumentException("模型不存在或已停用");
        return rows.get(0);
    }

    private ModelConfig map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ModelConfig(rs.getString("id"), rs.getString("provider"), rs.getString("model_name"),
                rs.getString("display_name"), rs.getBoolean("is_default"),
                rs.getDouble("input_price_per_million"), rs.getDouble("output_price_per_million"));
    }

    private Map<String, Object> toMap(ModelConfig model) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", model.id); result.put("provider", model.provider); result.put("model", model.model);
        result.put("displayName", model.displayName); result.put("default", model.isDefault);
        result.put("inputPricePerMillion", model.inputPrice); result.put("outputPricePerMillion", model.outputPrice);
        return result;
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class, table, column);
        if (count == null || count == 0) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private String normalize(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private String modelId(String provider, String model) { return (provider + "-" + model).replaceAll("[^a-zA-Z0-9._-]", "-").toLowerCase(); }
    private String displayName(String provider, String model) { return provider + " / " + model; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String required(Object value, String label) { String result=text(value); if(result.isEmpty()) throw new IllegalArgumentException(label+"不能为空"); return result; }
    private boolean bool(Object value, boolean fallback) { return value == null ? fallback : value instanceof Boolean ? (Boolean)value : Boolean.parseBoolean(text(value)); }
    private double number(Object value) { try { return Math.max(0d, Double.parseDouble(text(value))); } catch(Exception ignored){ return 0d; } }
    private int integer(Object value, int fallback) { try { return Integer.parseInt(text(value)); } catch(Exception ignored){ return fallback; } }

    public static final class ModelConfig {
        private final String id, provider, model, displayName;
        private final boolean isDefault;
        private final double inputPrice, outputPrice;
        public ModelConfig(String id, String provider, String model, String displayName, boolean isDefault, double inputPrice, double outputPrice) {
            this.id=id; this.provider=provider; this.model=model; this.displayName=displayName; this.isDefault=isDefault;
            this.inputPrice=inputPrice; this.outputPrice=outputPrice;
        }
        public String getId(){return id;} public String getProvider(){return provider;} public String getModel(){return model;}
        public String getDisplayName(){return displayName;} public boolean isDefault(){return isDefault;}
        public double getInputPrice(){return inputPrice;} public double getOutputPrice(){return outputPrice;}
    }
}
