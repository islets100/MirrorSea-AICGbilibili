### 1. `chat` 模块源码树状结构

以下是 `java/chat/src/main` 路径下的核心代码树状结构：

```text
src/main/
├── java/ljl/bilibili/chat/
│   ├── ChatApplication.java                   # 聊天模块的 Spring Boot 启动类
│   │
│   ├── config/                                # 核心配置类文件夹
│   │   ├── AsyncConfig.java                   # 异步线程池配置 (用于后台处理耗时任务)
│   │   ├── Knife4jConfiguration.java          # Knife4j API接口文档配置
│   │   └── WebSocketConfig.java               # WebSocket 配置类 (实时双向通信核心)
│   │
│   ├── constant/                              # 常量类文件夹
│   │   └── Constant.java                      # 存放聊天模块中到处使用的全局常量
│   │
│   ├── controller/                            # HTTP 控制器层
│   │   └── ChatController.java                # 暴漏给前端的普通 HTTP 接口 (如历史记录查询)
│   │
│   ├── entity/                                # 实体类 (通常映射了数据库表结构)
│   │   ├── ChatMessage.java                   # 聊天消息实体
│   │   ├── NoticeCount.java                   # 通知数量实体
│   │   ├── PPTDetail.java                     # PPT详情实体 (可能跟AI生成PPT功能有关)
│   │   └── PPTWord.java                       # PPT大纲文档相关实体
│   │
│   ├── event/                                 # 事件驱动与消息通信
│   │   └── MessageEvent.java                  # 封装消息事件 (用于 Spring Event 或消息队列的内部解耦传输)
│   │
│   ├── handler/                               # 重点！处理长连接及具体业务的核心处理器
│   │   ├── BigModelHandler.java               # 对接大模型的处理类 (处理与AI的聊天/生成逻辑)
│   │   ├── CustomAsyncExceptionHandler.java   # 异步代码执行时的异常统一捕获处理
│   │   ├── PPTHandler.java                    # 处理生成 PPT 任务的逻辑处理器
│   │   └── WebSocketHandler.java              # WebSocket 的收发消息控制中枢 
│   │
│   ├── mapper/                                # 数据访问层
│   │   └── ChatServiceMapper.java             # 操作聊天、通知、PPT 等数据库表相关的 SQL 映射查询
│   │
│   ├── ppt/                                   # 第三方或专门处理 PPT 生成功能的底层对接包
│   │   ├── ApiAuthAlgorithm.java              # 接口请求的签名算法（用于第三方 API 鉴权）
│   │   ├── ApiClient.java                     # 调用第三方 PPT 生成 API 的网络客户端
│   │   ├── CreateResponse.java                # 接收生成任务返回的结果实体
│   │   ├── Main.java                          # 可能作为测试使用，或者一个执行脚本入口
│   │   ├── MyUtil.java                        # PPT 功能封装的工具类
│   │   ├── OutlineVo.java                     # 大纲内容的视图对象
│   │   └── ProgressResponse.java              # 获取 PPT 生成进度的响应实体
│   │
│   ├── service/                               # 业务逻辑层接口及实现
│   │   ├── ChatService.java                   # 处理聊天 HTTP 请求或相关业务的接口
│   │   └── impl/
│   │       └── ChatServiceImpl.java           # 聊天相关 HTTP 业务的具体实现
│   │
│   └── vo/                                    # 视图对象 (用于与前端进行 HTTP JSON 数据交互)
│       ├── request/                           # 请求体
│       │   ├── AddHistoryChatRequest.java     # 新增/保存历史对话 请求
│       │   ├── ChangeChatStatusRequest.java   # 改变聊天状态 请求
│       │   └── ChatSessionRequest.java        # 聊天会话相关 请求
│       └── response/                          # 响应体
│           ├── ChatSessionResponse.java       # 返回会话列表结果
│           ├── HistoryChatResponse.java       # 返回历史记录查询结果
│           ├── ImageResponse.java             # 可能用于返回AI生图或者聊天发送图片的结果
│           ├── PPTResponse.java               # 返回生成PPT的状态和地址
│           └── TempSessionResponse.java       # 临时会话的结果封装
│
└── resources/                                 # 资源配置文件夹
    └── application.yml                        # 聊天模块的配置文件 (端口、数据库、Redis、AI API key等)
```

---

### 2. 文件夹及文件作用深度解析

和 `video` 模块传统的 HTTP 请求驱动（一问一答）不同，`chat` 模块具有非常明显的 **“实时通信”** 和 **“AI赋能/耗时任务”** 的特性。我们重点关注以下几个设计：

#### 实时通信的核心：`config` 与 `handler`
*   普通的网页点击是前端发一个 HTTP 请求给 Controller，后端返回 JSON 数据。但在聊天室里，消息是突然推送到你屏幕上的，这通常依赖于 **WebSocket** 技术。
*   在 **`config/WebSocketConfig.java`** 中，配置了 WebSocket 的注册器。
*   **`handler/WebSocketHandler.java`** 是整个聊天的“心脏”。它负责管理所有在线用户的连接（建立连接、断开连接），负责接收前端发来的消息，并判断这条消息是要转发给另外一个在线用户（私聊），还是要交给大模型（AI问答）。

#### AI集成与耗时操作：`handler` 与 `config/AsyncConfig.java`
*   调用大模型回答问题，或者生成一份几十页的 PPT 是极其耗时的（几秒甚至几十秒）。如果让用户一直卡着等，服务器也会崩溃。
*   因此项目中引入了 **`AsyncConfig.java`**（异步线程池）。它允许业务将“生成PPT”、“调用大模型”丢给后台线程去慢慢算。
*   **`handler/BigModelHandler.java`** 专门负责和大语言模型进行交互，处理流式输出（打字机效果）等。
*   **`handler/PPTHandler.java`** 和独立出来的 **`ppt/`** 子包，专门用于对接某一家第三方 AI-PPT 生成接口。`ppt/` 下面主要是这个第三方 API 要求的请求加密验证（`ApiAuthAlgorithm`）、状态轮询等代码。

#### 异步解耦：`event`
*   **`event/MessageEvent.java`** 是一种优雅的代码解耦设计。当 WebSocket 收到一条聊天消息后，可以不直接在 Handler 里去写数据库记录（因为存数据库慢），而是发布一个 `MessageEvent` 事件。由后台的其他监听器（Listener）默默地拿走这个事件，去负责保存进数据库，或者去触发未读消息红点（更新 `NoticeCount`）。

#### 常规的 CRUD：`controller` 与 `service`
*   除了需要长连接的实时对话外，聊天模块也需要常规的 HTTP 接口。比如：用户刚打开聊天界面，需要拉取过去的几个聊天会话框（`ChatSessionRequest`）或者拉取之前的漫游历史聊天记录（`HistoryChatResponse`）。
*   这一部分的职责落在了 **`ChatController.java`** 和 **`ChatService.java`** 等传统的 MVC 架构身上。

#### 数据载体：`entity` 与 `vo`
*   **`entity`** 是直接对应数据库设计的：比如你想给别人发消息，数据库里必须有一个表存 `ChatMessage`；想知道有几条弹幕还没看，数据库里存 `NoticeCount`。
*   **`vo`** 则是前台想要的结构封装，负责把生硬的数据库结构包装得丰富好用，或者承载像 `PPTResponse` 这种临时状态返回的数据载体。