# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

This repository is a front-end/back-end separated Bilibili-style project.

- `java/` is a Maven multi-module Spring Boot microservice backend targeting Java 8.
- `vue/` is a Vue 3 frontend application built with Vue CLI.
- `vue/labilibili/` is a second frontend app with the same overall structure and largely overlapping code; treat it as a parallel variant rather than a shared package.

## Common Commands

### Backend (`java/`)

- Build all modules: `mvn -f java/pom.xml clean package`
- Compile all modules only: `mvn -f java/pom.xml clean compile`
- Run all backend tests: `mvn -f java/pom.xml test`
- Run a single test: `mvn -f java/pom.xml -pl <module> -Dtest=<TestClass> test`
- Build one module and its dependencies: `mvn -f java/pom.xml -pl <module> -am package`
- Run a service with Maven: `mvn -f java/<module>/pom.xml spring-boot:run`

Backend modules:

- `gateway`
- `user_center`
- `video`
- `notice`
- `chat`
- `search`
- `common` (shared library, not a standalone service)

Main application classes:

- `java/gateway/src/main/java/ljl/bilibili/gateway/GateWayApplication.java`
- `java/user_center/src/main/java/ljl/bilibili/user_center/CenterEditApplication.java`
- `java/video/src/main/java/ljl/bilibili/video/VideoApplication.java`
- `java/notice/src/main/java/ljl/bilibili/notice/MessageApplication.java`
- `java/chat/src/main/java/ljl/bilibili/chat/ChatApplication.java`
- `java/search/src/main/java/ljl/bilibili/search/SearchApplication.java`

### Frontend (`vue/` or `vue/labilibili/`)

Run commands from the specific frontend directory you are editing.

- Install dependencies: `npm install`
- Start dev server: `npm run serve`
- Build production bundle: `npm run build`
- Lint: `npm run lint`
- Bundle with custom webpack entry: `npm run conduct`

Notes:

- The README mentions Node `18.20.2`.
- `vue/package.json` and `vue/labilibili/package.json` are both real app entry points; check which one the current change targets before editing.

## Architecture

### Backend microservice layout

The backend aggregator is defined in `java/pom.xml` and wires together six runnable Spring Boot services plus one shared module.

- `gateway`: API gateway and outer entry point. Routes HTTP and WebSocket traffic to downstream services, applies security-related request handling, and centralizes service exposure.
- `user_center`: user profile, follow relationships, personal center, and related user-facing account/domain endpoints.
- `video`: core content domain for upload/playback plus audience interactions such as comments, likes, favorites, danmaku, and video-to-collection flows.
- `notice`: notification and message status flows, with RocketMQ-backed async messaging responsibilities.
- `chat`: one-to-one chat and WebSocket-based real-time messaging.
- `search`: search aggregation, keyword lookup, and Elasticsearch-backed retrieval.
- `common`: shared cross-service library used by the runnable services.

### Shared backend infrastructure

`java/common/` contains the cross-cutting pieces that many services rely on:

- shared response wrapper (`util/Result.java`)
- global exception handling (`handler/GlobalExceptionHandler.java`)
- MyBatis auto-fill hooks (`handler/MyMetaObjectHandler.java`)
- shared serializers and filters
- shared entities/mappers/utilities
- Feign clients for direct inter-service calls

When changing service contracts, check whether the matching DTO/client/helper already lives in `common` before adding duplicates inside a single service.

### Gateway routing

The main local gateway mapping is configured in `java/gateway/src/main/resources/application-dev.yml`.

Important local routes:

- `/ensemble/**`, `/follow/**`, `/selfCenter/**`, `/userInfo/**` -> `user_center` (`localhost:3000`)
- `/collect/**`, `/comment/**`, `/danmaku/**`, `/videoToEnsemble/**`, `/like/**`, `/createCenter/**`, `/play/**` -> `video` (`localhost:10201`)
- `/changeNoticeStatus/**`, `/getNotice/**` -> `notice` (`localhost:30000`)
- `/chat/**` -> `chat` (`localhost:1688`)
- `/ljl/chat` -> chat WebSocket endpoint (`ws://localhost:1688`)
- `/search/**` -> `search` (`localhost:8201`)

If a frontend API call appears to be missing, inspect gateway routing first before assuming the target controller is wrong.

### Runtime dependencies and local environment

The services are designed around local infrastructure declared in the service `application.yml` files:

- MySQL database: `bilibili`
- Redis: `localhost`
- Nacos: `localhost:8848`
- MinIO: `localhost:9000`
- RocketMQ: `localhost:9876`
- Elasticsearch: `localhost:9200`
- Zipkin: `localhost:9411`
- XXL-Job: `localhost:8080/xxl-job-admin` (used by `search`)

Do not assume a service is independently runnable without its backing middleware.

## Frontend structure

Both `vue/` and `vue/labilibili/` follow the same app layout:

- `src/api/`: Axios request wrappers and API-specific modules
- `src/pages/`: route-level pages
- `src/components/`: reusable UI pieces
- `src/store/`: Pinia stores
- `src/router/`: route table and navigation guards
- `src/utils/`: app utilities, including persistence helpers
- `src/assets/` and `src/static/`: static resources

Important frontend patterns:

- Routing uses `createWebHistory()` and a large route table in `src/router/index.js`.
- Authentication gating happens in the global router guard via `meta.requireAuth`.
- API requests go through a shared Axios instance in `src/api/index.js` with `baseURL` set to `/api`.
- User/session state is stored with Pinia and `pinia-plugin-persistedstate`.
- The dev server proxy in `vue/vue.config.js` points `/api` and related paths at the deployed `https://www.labilibili.com`, so local frontend development is not automatically wired to locally running backend services.

Before changing frontend network behavior, verify whether the intended target is the production-like proxy, a local gateway, or both.

## Testing Reality

There is test infrastructure declared in Maven, but no standard backend `src/test` tree was found in the current repository snapshot.

Practical validation entry points are therefore:

- backend compilation/package commands under `java/`
- frontend `npm run build`
- frontend `npm run lint`

If you add tests, keep them module-local and run them with the module-specific Maven selector (`-pl`).

## Source Material Worth Trusting

- Root `README.md` explains the business scope and major technologies.
- `vue/README.md` and `vue/labilibili/README.md` document the frontend stack and Node version expectation.
- No `.cursorrules`, `.cursor/rules/`, or `.github/copilot-instructions.md` files were present when this file was created.
