# GaoChatbox View

一款支持多模型、多角色人设、本地优先存储的 Android AI 聊天应用。

## 功能特性

### 快速开始

- 以瀑布流网格展示所有角色预设（系统提示词）
- 点击卡片即可开始新对话
- 支持新增、编辑、删除自定义角色
- 支持拖拽排序自定义角色

### 聊天

- 流式传输实时显示 AI 响应
- Markdown 渲染（表格、链接、删除线等）
- 多模态输入：文本 + 图片/文件附件
- 工具调用：支持网页搜索，展示调用过程和结果
- 上下文压缩：自动管理历史消息以适应模型上下文窗口
- 自动为新对话生成标题
- 编辑对话标题、删除对话、新建对话
- 停止正在生成的响应
- 长按复制消息内容

### 历史记录

- 对话列表展示（标题、最后消息预览、相对时间戳）
- 关键词搜索对话
- 按标签筛选对话
- 长按查看调试日志或删除对话

### 设置

- **模型管理**：添加/编辑/删除 AI 模型配置
  - 支持 OpenAI 和 Anthropic API 类型
  - 自动获取可用模型列表
  - 自动检测上下文 token 限制
  - 温度参数调节
- **界面设置**：主题（系统/浅色/深色）、语言（系统/中文/英文）、显示字符数/Token数/模型名/时间戳
- **能力设置**：网页搜索开关、最大工具调用轮次

## 技术栈

| 项目 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | XML Layouts + Material Design 3 |
| 最低 SDK | 30 (Android 11) |
| 目标 SDK | 36 |
| Java 版本 | 17 |
| 架构 | MVVM (View → ViewModel → Repository → DataSource) |

### 依赖库

| 类别 | 库 |
|------|-----|
| UI | Material 3, ConstraintLayout, FlexBox, SplashScreen |
| 生命周期 | ViewModel, LiveData, Lifecycle Runtime |
| 数据库 | Room (KSP) |
| 网络 | Retrofit, OkHttp |
| 偏好存储 | DataStore |
| JSON | Gson |
| Markdown | Markwon (Core, Tables, Linkify, Strikethrough) |
| 适配器 | BRVAH (BaseRecyclerViewAdapterHelper) |
| 依赖注入 | Dagger (KSP) |
| 异步 | Kotlin Coroutines |

## 项目结构

```
app/src/main/java/com/gao/chatbox/view/
├── ChatBoxApp.kt                    # Application 类
├── MainActivity.kt                  # 主页入口
├── di/                              # 依赖注入
│   ├── AppComponent.kt              # Dagger 组件接口
│   └── AppModule.kt                 # Dagger 模块
├── data/
│   ├── local/db/                    # Room 数据库
│   │   ├── AppDatabase.kt           # 数据库定义
│   │   ├── ChatDatabaseManager.kt   # 数据库操作管理
│   │   ├── dao/                     # DAO 接口
│   │   └── entity/                  # 实体类
│   ├── model/
│   │   ├── ModelConfig.kt           # 模型配置数据类
│   │   └── SystemPrompt.kt          # 系统提示词数据类
│   ├── remote/                      # 网络 API
│   │   ├── OpenAiApi.kt             # OpenAI API 接口
│   │   ├── AnthropicApi.kt          # Anthropic API 接口
│   │   └── SseParser.kt             # SSE 流解析器
│   └── repository/
│       └── ChatRepository.kt        # 聊天仓库
├── ui/
│   ├── home/                        # 主页（3 个 Tab）
│   │   ├── quickstart/              # 快速开始
│   │   ├── history/                 # 历史记录
│   │   └── settings/                # 设置
│   ├── chat/                        # 聊天页面
│   │   ├── ChatActivity.kt          # 聊天 Activity
│   │   ├── ChatViewModel.kt         # 聊天 ViewModel
│   │   ├── ChatAdapter.kt           # 消息列表适配器
│   │   ├── ChatItem.kt              # 消息 Item 密封类
│   │   ├── ChatRenderSettings.kt    # 渲染设置
│   │   ├── ChatItemBuilder.kt       # 时间戳构建器
│   │   └── ContextCompressionPlanner.kt  # 上下文压缩规划器
│   └── debug/
│       └── DebugLogActivity.kt      # 调试日志页面
└── util/
    ├── ApiClient.kt                 # API 客户端工具
    ├── ModelConfigManager.kt        # 模型配置管理
    ├── ModelContextLimitResolver.kt # 上下文限制解析
    ├── SystemPromptManager.kt       # 系统提示词管理
    ├── DebugLogManager.kt           # 调试日志管理
    ├── WebSearchTool.kt             # 网页搜索工具
    ├── LanguageManager.kt           # 语言管理
    └── ThemeManager.kt              # 主题管理
```

## 构建与运行

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 30+

### 构建命令

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 运行单元测试
./gradlew test

# 运行设备测试
./gradlew connectedAndroidTest

# 清理构建
./gradlew clean assembleDebug
```

## 代码规范

- 使用 ViewBinding 引用布局（不使用 `findViewById`）
- 使用 Kotlin Coroutines 处理异步操作
- 遵循 MVVM 架构模式
- Room 实体使用 `@Entity` 注解，DAO 返回 `Flow<List<T>>` 实现响应式查询
- 使用密封类定义 UI 状态
- 使用 Material 3 组件和主题

## 开源许可

本项目仅供学习交流使用。
