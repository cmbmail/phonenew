---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'f4681124-f6e9-4cf6-8276-8d5548d6efe2'
  PropagateID: 'f4681124-f6e9-4cf6-8276-8d5548d6efe2'
  ReservedCode1: 'aa0c8ef5-927e-4413-874f-e2a38af27673'
  ReservedCode2: 'aa0c8ef5-927e-4413-874f-e2a38af27673'
---

# 银行电话费用分摊系统

银行内部电信账单在多级组织架构中的自动化费用分摊系统。

## 技术栈

| 层 | 技术 |
|---|------|
| 前端 | React 18 + Ant Design 5 + Zustand 5 + React Query 5 + Vite 5 + TypeScript |
| 后端 | Spring Boot 3.2.5 + JPA (Hibernate) + Flyway |
| 数据库 | MySQL 8.0.46 |
| 缓存 | Redis 7.2.14 |
| 认证 | JWT (jjwt 0.12.5) + BCrypt |

## 目录结构

```
├── backend/phonecost/          # Spring Boot 后端
│   ├── src/main/java/          # Java 源码 (83 文件)
│   ├── src/main/resources/     # 配置 + Flyway 迁移 (V1-V10)
│   └── pom.xml
├── frontend/phonecost/         # React 前端
│   ├── src/                    # TypeScript 源码 (49 文件)
│   └── vite.config.ts
├── deploy/                     # 部署相关
│   ├── deploy.sh               # 一键部署脚本
│   └── nginx.conf              # Nginx 配置
├── .env.example                # 环境变量模板
├── 银行电话费用分摊系统_DDL_V1.sql   # 数据库建表脚本
└── 银行电话费用分摊系统_*.docx       # 设计文档
```

## 快速部署

### 1. 环境准备

服务器需要: Java 21, Maven 3.9+, MySQL 8.0, Redis 7, Nginx

```bash
# 创建数据库和用户
mysql -u root -p < 银行电话费用分摊系统_DDL_V1.sql
```

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 填入实际密码
```

必填变量: `DB_PASSWORD`, `REDIS_PASSWORD`, `JWT_SECRET`

### 3. 一键部署

```bash
# 全量部署（前端+后端）
bash deploy/deploy.sh

# 仅部署前端
bash deploy/deploy.sh --frontend-only

# 仅部署后端
bash deploy/deploy.sh --backend-only
```

### 4. 验证

```bash
# 健康检查
curl http://localhost:8080/api/health

# 前端页面
curl -k https://localhost/
```

## 系统架构

- **组织层级**: 集团 → 一级分行(30+) → 二级分行/支行(900+) → 部门(数千)
- **角色体系**: 管理员(1) / 分行(2) / 部门(3) / 财务(4)
- **费用分摊**: 按号码归属自动归集，四级优先级匹配 (P0例外>P1通讯录>P2号码归属>P3未归属)
- **确认流程**: 逐级确认可撤回，乐观锁防并发

## 测试

8类249项测试全部通过: 功能58 / 性能18 / 安全26 / 兼容性19 / 异常容错36 / UI/UX35 / 部署运维32 / 数据25