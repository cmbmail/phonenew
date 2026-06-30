#!/bin/bash
# ============================================================
# 银行电话费用分摊系统 — 一键部署脚本
# 用法: bash deploy.sh [--frontend-only|--backend-only]
# ============================================================
set -e

SSH_TARGET="${SSH_TARGET:-openeuler3-phone0622@orb}"
REMOTE_DIR="/data/apps/phonecost"
LOCAL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SSH_OPTS="-o ConnectTimeout=10 -o ServerAliveInterval=5"

DEPLOY_FRONTEND=true
DEPLOY_BACKEND=true

if [ "$1" = "--frontend-only" ]; then DEPLOY_BACKEND=false; fi
if [ "$1" = "--backend-only" ]; then DEPLOY_FRONTEND=false; fi

echo "============================================================"
echo "  银行电话费用分摊系统 — 部署"
echo "  目标: $SSH_TARGET"
echo "  前端: $DEPLOY_FRONTEND  后端: $DEPLOY_BACKEND"
echo "============================================================"

# --- Frontend ---
if $DEPLOY_FRONTEND; then
  echo ""
  echo "=== 1. 构建前端 ==="
  cd "$LOCAL_DIR/frontend/phonecost"
  npx vite build
  echo ""

  echo "=== 2. 上传前端 ==="
  tar czf /tmp/phonecost-dist.tar.gz -C dist .
  scp $SSH_OPTS /tmp/phonecost-dist.tar.gz "$SSH_TARGET:/tmp/phonecost-dist.tar.gz"
  ssh $SSH_OPTS "$SSH_TARGET" "sudo rm -rf $REMOTE_DIR/frontend/dist/* && sudo tar xzf /tmp/phonecost-dist.tar.gz -C $REMOTE_DIR/frontend/dist/"
  echo "前端部署完成"
fi

# --- Backend ---
if $DEPLOY_BACKEND; then
  echo ""
  echo "=== 3. 上传后端源码 ==="
  cd "$LOCAL_DIR"
  tar czf /tmp/phonecost-backend.tar.gz --exclude='backend/phonecost/target' backend/phonecost/
  scp $SSH_OPTS /tmp/phonecost-backend.tar.gz "$SSH_TARGET:/tmp/phonecost-backend.tar.gz"
  echo ""

  echo "=== 4. 远程构建 + 重启 ==="
  ssh $SSH_OPTS "$SSH_TARGET" bash -s << 'REMOTE_SCRIPT'
set -e
cd /data/apps/phonecost
rm -rf backend/phonecost
tar xzf /tmp/phonecost-backend.tar.gz
cd backend/phonecost

# Workaround: Hibernate @ColumnDefault import
for f in $(grep -rl '@ColumnDefault' src/main/java/ --include='*.java'); do
  grep -q 'import org.hibernate.annotations.ColumnDefault' "$f" || sed -i '/^import /a import org.hibernate.annotations.ColumnDefault;' "$f"
done

# Workaround: MySQL charset param name
sed -i 's/characterEncoding=utf8mb4/characterEncoding=UTF-8/g' src/main/resources/application-prod.yml

# Build
rm -rf target
/usr/local/bin/mvn package -DskipTests -q

# Restart
sudo systemctl restart phonecost-backend
echo "后端已重启"
REMOTE_SCRIPT
fi

# --- Verify ---
echo ""
echo "=== 5. 等待服务启动 ==="
sleep 8

echo "=== 6. 健康检查 ==="
HEALTH=$(ssh $SSH_OPTS "$SSH_TARGET" "curl -s http://localhost:8080/api/health")
echo "  后端: $HEALTH"

FRONTEND_STATUS=$(ssh $SSH_OPTS "$SSH_TARGET" "curl -sk -o /dev/null -w '%{http_code}' https://localhost/")
echo "  前端: HTTP $FRONTEND_STATUS"

echo ""
echo "============================================================"
if echo "$HEALTH" | grep -q '"ok"' && [ "$FRONTEND_STATUS" = "200" ]; then
  echo "  部署成功"
else
  echo "  部署异常，请检查服务状态"
fi
echo "============================================================"
