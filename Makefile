# LeapAgent Makefile
# 用于自动化项目初始化和文档向量化

# 配置变量
SERVER_URL = http://localhost:9900
UPLOAD_API = $(SERVER_URL)/api/upload
DOCS_DIR = aiops-docs
HEALTH_CHECK_API = $(SERVER_URL)/milvus/health
DOCKER_COMPOSE_FILE = vector-database.yml
MILVUS_CONTAINER = milvus-standalone
MAKE = make

# 颜色输出
GREEN = \033[0;32m
YELLOW = \033[0;33m
RED = \033[0;31m
NC = \033[0m # No Color

.PHONY: help init start stop restart check upload clean up down status wait

# 默认目标：显示帮助信息
help:
	@echo "LeapAgent Makefile"
	@echo ""
	@echo "可用命令："
	@echo "  make init    - 一键初始化（启动Docker → 启动服务 → 上传文档）"
	@echo "  make up      - 启动 Docker Compose（Milvus 向量数据库）"
	@echo "  make down    - 停止 Docker Compose"
	@echo "  make status  - 查看 Docker 容器状态"
	@echo "  make start   - 启动 Spring Boot 服务（后台运行）"
	@echo "  make stop    - 停止 Spring Boot 服务"
	@echo "  make restart - 重启 Spring Boot 服务"
	@echo "  make check   - 检查服务器是否运行"
	@echo "  make upload  - 上传 aiops-docs 目录下的所有文档"
	@echo "  make clean   - 清理临时文件"
	@echo ""
	@echo "使用示例："
	@echo "  1. 一键初始化: make init"
	@echo "  2. 手动启动: make up && make start && make upload"
	@echo "  3. 停止服务: make stop && make down"

# 一键初始化：启动Docker → 启动服务 → 检查服务 → 上传文档
init:
	@echo "? 开始一键初始化 LeapAgent..."
	@echo ""
	@echo "步骤 1/4: 启动 Docker Compose（Milvus 向量数据库）"
	@$(MAKE) up
	@echo ""
	@echo "步骤 2/4: 启动 Spring Boot 服务"
	@$(MAKE) start
	@echo ""
	@echo "步骤 3/4: 等待服务就绪"
	@$(MAKE) wait
	@echo ""
	@echo "步骤 4/4: 上传 AIOps 文档到向量数据库"
	@$(MAKE) upload
	@echo ""
	@echo "═══════════════════════════════════════════════════════"
	@echo "? 初始化完成！所有文档已成功向量化存储到数据库"
	@echo "═══════════════════════════════════════════════════════"
	@echo ""
	@echo "? 服务访问地址:"
	@echo "   API 服务: $(SERVER_URL)"
	@echo "   Attu (Web UI): http://localhost:8000"
	@echo ""
	@echo "? 提示: 服务正在后台运行，查看日志: tail -f server.log"

# 启动 Spring Boot 服务（后台运行）
start:
	@echo "? 启动 Spring Boot 服务..."
	@if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
		echo "? 服务已经在运行中 ($(SERVER_URL))"; \
	else \
		echo "? 正在启动服务（后台运行）..."; \
		nohup mvn spring-boot:run > server.log 2>&1 & \
		echo $$! > server.pid; \
		echo "? 服务启动命令已执行"; \
		echo "   PID: $$(cat server.pid)"; \
		echo "   日志文件: server.log"; \
	fi

# 等待服务器就绪（最多等待 60 秒）
wait:
	@echo "? 等待服务器就绪..."
	@max_attempts=60; \
	attempt=0; \
	while [ $$attempt -lt $$max_attempts ]; do \
		if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
			echo "? 服务器已就绪！($(SERVER_URL))"; \
			exit 0; \
		fi; \
		attempt=$$((attempt + 1)); \
		printf "   等待中... [$$attempt/$$max_attempts]\r"; \
		sleep 1; \
	done; \
	echo ""; \
	echo "$(RED)? 服务器启动超时！"; \
	echo "请检查日志: tail -f server.log"; \
	exit 1

# 检查服务器是否运行
check:
	@echo "? 检查服务器状态..."
	@if curl -s -f $(HEALTH_CHECK_API) > /dev/null 2>&1; then \
		echo "? 服务器运行正常 ($(SERVER_URL))"; \
	else \
		echo "$(RED)? 服务器未运行或无法连接！"; \
		echo "请先启动项目: mvn spring-boot:run"; \
		exit 1; \
	fi

# 上传所有文档
upload:
	@echo "? 开始上传 $(DOCS_DIR) 目录下的文档..."
	@if [ ! -d "$(DOCS_DIR)" ]; then \
		echo "$(RED)? 目录 $(DOCS_DIR) 不存在！"; \
		exit 1; \
	fi
	@count=0; \
	success=0; \
	failed=0; \
	for file in $(DOCS_DIR)/*.md; do \
		if [ -f "$$file" ]; then \
			count=$$((count + 1)); \
			filename=$$(basename "$$file"); \
			echo "  [$$count] 上传文件: $$filename"; \
			response=$$(curl -s -w "\n%{http_code}" -X POST $(UPLOAD_API) \
				-F "file=@$$file" \
				-H "Accept: application/json"); \
			http_code=$$(echo "$$response" | tail -n1); \
			body=$$(echo "$$response" | sed '$$d'); \
			if [ "$$http_code" = "200" ]; then \
				echo "      ? 成功: $$filename"; \
				success=$$((success + 1)); \
			else \
				echo "$(RED)      ? 失败: $$filename (HTTP $$http_code)"; \
				echo "$$body" | head -n 3; \
				failed=$$((failed + 1)); \
			fi; \
			sleep 1; \
		fi; \
	done; \
	echo ""; \
	echo "? 上传统计:"; \
	echo "   总计: $$count 个文件"; \
	echo "   成功: $$success"; \
	if [ $$failed -gt 0 ]; then \
		echo "   $(RED)失败: $$failed"; \
	fi

# 停止 Spring Boot 服务
stop:
	@echo "? 停止 Spring Boot 服务..."
	@if [ -f server.pid ]; then \
		pid=$$(cat server.pid); \
		if ps -p $$pid > /dev/null 2>&1; then \
			kill $$pid; \
			echo "? 服务已停止 (PID: $$pid)"; \
		else \
			echo "??  进程不存在 (PID: $$pid)"; \
		fi; \
		rm -f server.pid; \
	else \
		echo "??  未找到 server.pid 文件"; \
		pkill -f "spring-boot:run" && echo "? 已停止所有 spring-boot 进程" || echo "??  没有运行中的 spring-boot 进程"; \
	fi

# 重启 Spring Boot 服务
restart:
	@echo "? 重启 Spring Boot 服务..."
	@echo ""
	@echo "步骤 1/2: 停止服务"
	@$(MAKE) stop
	@echo ""
	@echo "步骤 2/2: 启动服务"
	@$(MAKE) start
	@echo ""
	@$(MAKE) wait
	@echo ""
	@echo "? 服务重启完成！"

# 清理临时文件
clean:
	@echo "? 清理临时文件..."
	@rm -rf uploads/*.tmp
	@rm -f server.pid server.log
	@echo "? 清理完成"

# 显示文档列表
list-docs:
	@echo "? $(DOCS_DIR) 目录下的文档:"
	@if [ -d "$(DOCS_DIR)" ]; then \
		ls -lh $(DOCS_DIR)/*.md 2>/dev/null || echo "$(RED)没有找到 .md 文件"; \
	else \
		echo "$(RED)目录 $(DOCS_DIR) 不存在"; \
	fi

# 测试单个文件上传
test-upload:
	@echo "? 测试上传单个文件..."
	@if [ -f "$(DOCS_DIR)/cpu_high_usage.md" ]; then \
		curl -X POST $(UPLOAD_API) \
			-F "file=@$(DOCS_DIR)/cpu_high_usage.md" \
			-H "Accept: application/json" | jq .; \
	else \
		echo "$(RED)测试文件不存在"; \
	fi

# 启动 Docker Compose（智能检测，避免重复启动）
up:
	@echo "? 检查 Docker 容器状态..."
	@if [ ! -f "$(DOCKER_COMPOSE_FILE)" ]; then \
		echo "$(RED)? Docker Compose 文件不存在: $(DOCKER_COMPOSE_FILE)"; \
		exit 1; \
	fi
	@if docker ps --format '{{.Names}}' | grep -q "^$(MILVUS_CONTAINER)$$"; then \
		echo "? Milvus 容器已经在运行中"; \
		echo "? 当前运行的容器:"; \
		docker ps --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
	else \
		echo "? 启动 Docker Compose..."; \
		docker-compose -f $(DOCKER_COMPOSE_FILE) up -d; \
		echo ""; \
		echo "? 等待容器启动..."; \
		sleep 5; \
		if docker ps --format '{{.Names}}' | grep -q "^$(MILVUS_CONTAINER)$$"; then \
			echo "? Docker Compose 启动成功！"; \
			echo ""; \
			echo "? 运行中的容器:"; \
			docker ps --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
			echo ""; \
			echo "? 服务访问地址:"; \
			echo "   Milvus: localhost:19530"; \
			echo "   Attu (Web UI): http://localhost:8000"; \
			echo "   MinIO: http://localhost:9001 (admin/minioadmin)"; \
		else \
			echo "$(RED)? 容器启动失败，请检查日志: docker-compose -f $(DOCKER_COMPOSE_FILE) logs"; \
			exit 1; \
		fi; \
	fi

# 停止 Docker Compose
down:
	@echo "? 停止 Docker Compose..."
	@if [ ! -f "$(DOCKER_COMPOSE_FILE)" ]; then \
		echo "$(RED)? Docker Compose 文件不存在: $(DOCKER_COMPOSE_FILE)"; \
		exit 1; \
	fi
	@if docker ps --format '{{.Names}}' | grep -q "milvus"; then \
		docker-compose -f $(DOCKER_COMPOSE_FILE) down; \
		echo "? Docker Compose 已停止"; \
	else \
		echo "??  没有运行中的 Milvus 容器"; \
	fi

# 查看 Docker 容器状态
status:
	@echo "? Docker 容器状态:"
	@echo ""
	@if docker ps -a --format '{{.Names}}' | grep -q "milvus"; then \
		docker ps -a --filter "name=milvus" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"; \
		echo ""; \
		running=$$(docker ps --filter "name=milvus" --format '{{.Names}}' | wc -l | tr -d ' '); \
		total=$$(docker ps -a --filter "name=milvus" --format '{{.Names}}' | wc -l | tr -d ' '); \
		echo "运行中: $$running / $$total"; \
	else \
		echo "??  没有找到 Milvus 相关容器"; \
		echo "提示: 运行 'make docker-up' 启动容器"; \
	fi
