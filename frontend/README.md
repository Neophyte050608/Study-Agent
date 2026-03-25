# Frontend 模块

## 本地开发
```bash
npm install
npm run dev
```

默认开发端口 `5173`，`/api` 会代理到 `http://localhost:9596`。

## 构建到 Spring Boot 静态目录
```bash
npm run build:spring
```

构建结果输出到 `src/main/resources/static/spa`。
