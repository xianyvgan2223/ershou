# 商品搜索用的 Elasticsearch 镜像（compose 默认构建这一层）
#
#   docker compose up -d --build elasticsearch      # 重新构建（插件源更新时）
#   不想装插件：删掉 docker-compose.yml 里 elasticsearch 的 build 段、image 改回 elasticsearch:8.13.4
#
# 依赖顺序：IK（第三方镜像源）→ 官方 analysis-smartcn → 都不装（后端自动退化为 ngram）
ARG ES_BASE_IMAGE=elasticsearch:8.13.4
FROM ${ES_BASE_IMAGE}

ARG IK_URL=https://get.infini.cloud/elasticsearch/analysis-ik/8.13.4

RUN bin/elasticsearch-plugin install --batch "${IK_URL}" \
 || bin/elasticsearch-plugin install --batch analysis-smartcn \
 || echo "WARN: 中文分词插件安装失败，索引将退化为内置 ngram 分析器"

# ---- 自定义词典 + 同义词表 ----
# custom.dic   → IK 扩展词典(ext_dict)：把简称/校园词切成整体词，例如「高数」「电驴」「一卡通」
# synonyms.txt → 查询期同义词(synonym_graph)：高数 ⇄ 高等数学、ipad ⇄ 平板 …
#                改完只需 POST /<索引>/_reload_search_analyzers 即可生效，不必重建索引
RUN mkdir -p /usr/share/elasticsearch/config/analysis-ik \
 && chown 1000:0 /usr/share/elasticsearch/config/analysis-ik
COPY --chown=1000:0 custom.dic synonyms.txt /usr/share/elasticsearch/config/analysis-ik/
RUN set -e; \
    cfg=/usr/share/elasticsearch/config/analysis-ik/IKAnalyzer.cfg.xml; \
    if [ -f "$cfg" ]; then \
      sed -i 's#<entry key="ext_dict"></entry>#<entry key="ext_dict">custom.dic</entry>#' "$cfg"; \
      grep -q '<entry key="ext_dict">custom.dic</entry>' "$cfg" \
        || echo "WARN: ext_dict 未写入成功，自定义词典不生效"; \
    else \
      echo "WARN: 未装 IK，custom.dic 不生效；同义词表仍可给 smartcn 使用"; \
    fi; \
    chmod 660 /usr/share/elasticsearch/config/analysis-ik/custom.dic \
              /usr/share/elasticsearch/config/analysis-ik/synonyms.txt
