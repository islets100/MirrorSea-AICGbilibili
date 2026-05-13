src/main/
├── java/ljl/bilibili/video/
│   ├── VideoApplication.java                  # 模块启动类
│   ├── config/                                # 配置类文件夹
│   │   ├── Knife4jConfiguration.java          # Knife4j (Swagger增强版) 接口文档配置
│   │   ├── MinioConfig.java                   # MinIO (对象存储) 配置类
│   │   └── RedisConfig.java                   # Redis 缓存配置类
│   │
│   ├── constant/                              # 常量类文件夹
│   │   └── Constant.java                      # 定义模块中到处使用的全局常量
│   │
│   ├── controller/                            # 控制层文件夹 (处理 HTTP 请求)
│   │   ├── all_table_data/                    # 获取所有表数据的控制器
│   │   ├── audience_reactions/                # 观众互动功能控制器 (如点赞、评论等)
│   │   │   ├── collect/CollectController.java # 收藏功能接口
│   │   │   ├── comment/CommentController.java # 评论功能接口
│   │   │   ├── danmaku/DanmakuController.java # 弹幕功能接口
│   │   │   ├── like/LikeController.java       # 点赞功能接口
│   │   │   └── play/PlayController.java       # 播放相关接口 (历史记录、推荐视频等)
│   │   └── video_production/                  # 视频创作者相关控制器
│   │       ├── UploadAndEditController.java   # 视频上传与信息编辑接口
│   │       └── VideoEncodeController.java     # 视频编码处理接口
│   │
│   ├── mapper/                                # 数据访问层文件夹 (MyBatis 或 MP 接口)
│   │   └── VideoServiceMapper.java            # 与数据库进行视频相关交互的 SQL 映射接口
│   │
│   ├── pojo/                                  # 普通 Java 对象 (此处可能是实体或业务对象)
│   │   └── UploadPart.java                    # 分片上传相关的实体对象
│   │
│   ├── service/                               # 业务逻辑层文件夹 (核心业务逻辑)
│   │   ├── all_table_data/                    # 数据汇总相关服务
│   │   ├── audience_reactions/                # 观众互动功能业务逻辑 (按功能分包)
│   │   │   ├── collect/                       # 收藏功能业务 (包含接口及其 impl 实现类)
│   │   │   ├── comment/                       # 评论功能业务 
│   │   │   ├── danmaku/                       # 弹幕功能业务
│   │   │   ├── like/                          # 点赞功能业务
│   │   │   └── play/                          # 播放相关业务
│   │   └── video_production/                  # 视频上传、编辑与存储逻辑
│   │       ├── MinioService.java              # 封装 MinIO 的文件上传/删除服务
│   │       ├── UploadAndEditService.java      # 处理视频投稿、分片上传逻辑
│   │       └── impl/                          # 具体的业务处理实现类
│   │
│   └── vo/                                    # 视图对象文件夹 (View Object，用于前后端数据交互)
│       ├── request/                           # 所有的请求体 (接收前端的参数封装)
│       │   ├── audience_reactions/            # 分类如 CommentRequest, LikeRequest 
│       │   └── video_production/              # 分类如 UploadVideoRequest 
│       └── response/                          # 所有的响应体 (返回给前端的数据封装)
│           ├── audience_reactions/            # 如 CommentResponse, DanmakuResponse
│           └── video_production/              # 如 UploadProcessorResponse
│
└── resources/                                 # 资源配置文件夹
    └── application.yml                        # 模块的 Spring Boot 配置文件 (如端口、数据库连接信息等)

---

### 2. 文件夹及文件作用解析

这套结构是经典的 **MVC 分层架构**融合了**领域驱动分类**的设计方式。

#### 核心启动与配置
*   **`VideoApplication.java`**: Spring Boot 启动类。包含 `@SpringBootApplication` 注解，是当前微服务启动的入口。
*   **`config/` (配置层)**: 存放连接第三方组件的配置。
    *   `MinioConfig`: 用于创建并配置连接到 MinIO 存储服务器的 Client 对象，视频的物理文件一定存在该存储里。
    *   `RedisConfig`: 用于对 Redis 序列化缓存等功能的定制，系统的高频查询或点赞/弹幕通常会先落到 Redis。
    *   `Knife4jConfiguration`: 用于自动生成 RESTful API 接口文档，方便前端联调。

#### 控制器层 (Controller)
*   **`controller/`**: 暴露给前端（或外部服务）调用的 HTTP 接口（也就是路由口在哪），分为 3 大模块：
    *   `audience_reactions/`: 观众的行为。由前端用户触发的操作，包含了**评论 (`comment`)**、**点赞 (`like`)**、**弹幕 (`danmaku`)**、**收藏 (`collect`)** 和 **播放行为 (`play`)** 等行为对应的数据获取与提交接口。
    *   `video_production/`: UP 主创作者的行为。控制视频文件的上传（通常支持分片上传）、信息的编写及转码回调。

#### 业务逻辑层 (Service)
*   **`service/`**: 这是后端最核心的环节，用来处理具体功能背后的逻辑（比如：发个评论，先检查视频在不在，再校验敏感词，最后写入数据库），与 Controller 的分类保持一致。
    *   接口与实现类分离（如 `PlayService` 接口和 `PlayServiceImpl` 实现类）是为了更好地实现解耦与 AOP 代理。
    *   **特殊服务 `MinioService`**: 将对对象存储库（MinIO）的操作（如生成上传 URL、合并分片等）抽取成公共服务。

#### 数据访问层 (Mapper)
*   **`mapper/`**: 与数据库（通常是 MySQL）打交道。由于此项目未见大量 Mapper 文件，推测 `VideoServiceMapper` 集中处理了跨表的复杂查询，或者通过 MyBatis-Plus 等 ORM 框架自动实现了单表的增删改查。

#### 数据载体层 (VO & POJO)
*   **`vo/` (View Object)**: 专门用于与前端进行接口交互传输的包装载体。
    *   `request/`: 收到的。当前端发来 JSON 格式的请求时，Spring Boot 会将其自动映射为相对应的 Request 对象。比如你正在看的 `CommentRequest.java` 就是用来承载**发送评论时所需的参数**（视频id、评论内容等）。
    *   `response/`: 发出的。服务处理完后，需要把结果或数据封装进如 `CommentResponse` 等类中，统一转换为 JSON 格式响应给前端。
*   **`pojo/`**: 简单的 Java 对象，比如此处的上传分片信息实体类。

#### 静态配置 (Resources)
*   **`resources/application.yml`**: 本地全局配置文件，记录该模块监听的端口号、数据库 URL、密码、Redis和Minio的连接信息等环境数据。


    


