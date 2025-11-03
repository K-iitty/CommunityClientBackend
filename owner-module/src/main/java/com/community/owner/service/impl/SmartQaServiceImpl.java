package com.community.owner.service.impl;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.community.owner.mapper.*;
import com.community.owner.dto.QaRequest;
import com.community.owner.entity.*;
import com.community.owner.service.OwnerService;
import com.community.owner.service.SmartQaService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能问答服务实现类
 * 使用Spring AI + RAG方式实现
 */
@Service
public class SmartQaServiceImpl implements SmartQaService {
    
    @Autowired
    private SmartQaKnowledgeMapper knowledgeDao;
    
    @Autowired
    @Lazy
    private OwnerService ownerService;
    
    @Autowired
    private HouseOwnerMapper houseOwnerMapper;
    
    @Autowired
    private HouseMapper houseDao;
    
    @Autowired
    private VehicleMapper vehicleMapper;
    
    @Autowired
    private MeterInfoMapper meterInfoMapper;
    
    @Autowired
    private DashScopeChatModel chatModel;
    
    @Override
    public Flux<String> streamChat(QaRequest request, Long ownerId) {
        try {
            // 验证请求参数
            if (request == null || request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                return Flux.just("请输入有效的问题。");
            }
            
            // 1. 获取当前业主信息（确保不返回 null）
            String ownerInfo = "";
            try {
                ownerInfo = retrieveOwnerInfo(ownerId);
                if (ownerInfo == null) {
                    ownerInfo = "";
                }
            } catch (Exception e) {
                ownerInfo = "业主信息：查询异常";
            }
            
            // 2. 从知识库检索相关信息（RAG）（确保不返回 null）
            String knowledgeContext = "";
            try {
                knowledgeContext = retrieveKnowledge(request.getQuestion());
                if (knowledgeContext == null) {
                    knowledgeContext = "";
                }
            } catch (Exception e) {
                knowledgeContext = "";
            }
            
            // 3. 从数据库检索相关信息（基于问题关键词）（确保不返回 null）
            String databaseContext = "";
            try {
                databaseContext = retrieveDatabaseInfo(request.getQuestion(), ownerId);
                if (databaseContext == null) {
                    databaseContext = "";
                }
            } catch (Exception e) {
                databaseContext = "";
            }
            
            // 4. 判断是否有有效信息
            boolean hasLocalInfo = hasValidLocalInfo(knowledgeContext, databaseContext);
            
            // 5. 构建系统提示词
            String systemPrompt = buildSystemPrompt(ownerInfo, knowledgeContext, databaseContext, hasLocalInfo);
            
            // 6. 构建消息列表（包含历史对话，支持多轮对话）
            List<Message> messages = buildMessages(systemPrompt, request);
            
            // 7. 调用通义千问模型（流式输出）- 使用 Spring AI Alibaba
            Prompt prompt = new Prompt(messages, DashScopeChatOptions.builder()
                    .withModel("qwen-max")
                    .withTemperature(0.7)
                    .build());
            
            // 8. 返回流式响应（不过滤空内容，让模型决定输出）
            return chatModel.stream(prompt)
                    .map(response -> {
                        if (response != null && response.getResult() != null) {
                            var output = response.getResult().getOutput();
                            if (output != null) {
                                String text = output.getText();
                                return text != null ? text : "";
                            }
                        }
                        return "";
                    })
                    .onErrorResume(e -> {
                        // 如果流式输出出错，返回错误信息
                        return Flux.just("抱歉，智能问答服务暂时不可用，请稍后再试。错误信息：" + e.getMessage());
                    });
                    
        } catch (Exception e) {
            // 捕获所有异常，返回友好的错误信息
            return Flux.just("抱歉，处理您的问题时出现异常，请稍后再试。");
        }
    }
    
    /**
     * 获取当前业主基本信息
     */
    private String retrieveOwnerInfo(Long ownerId) {
        try {
            if (ownerId == null) {
                return "";
            }
            
            Owner owner = ownerService.getById(ownerId);
            if (owner == null) {
                return ""; // 未找到业主信息时返回空字符串，不影响后续流程
            }
            
            StringBuilder info = new StringBuilder();
            info.append("【当前业主信息】\n");
            
            // 安全地获取业主姓名
            if (owner.getName() != null && !owner.getName().isEmpty()) {
                info.append("姓名：").append(owner.getName()).append("\n");
            }
            
            // 安全地获取业主类型
            String ownerType = owner.getOwnerType() != null && !owner.getOwnerType().isEmpty() 
                    ? owner.getOwnerType() : "业主";
            info.append("业主类型：").append(ownerType).append("\n");
            
            // 查询业主的主要房屋信息（失败不影响后续）
            try {
                if (houseOwnerMapper != null) {
                    QueryWrapper<HouseOwner> wrapper = new QueryWrapper<>();
                    wrapper.eq("owner_id", ownerId)
                           .eq("status", "正常")
                           .eq("is_primary", 1)
                           .last("LIMIT 1");
                    HouseOwner houseOwner = houseOwnerMapper.selectOne(wrapper);
                    
                    if (houseOwner != null && houseOwner.getHouseId() != null && houseDao != null) {
                        House house = houseDao.selectById(houseOwner.getHouseId());
                        if (house != null) {
                            if (house.getFullRoomNo() != null) {
                                info.append("房屋：").append(house.getFullRoomNo()).append("\n");
                            }
                            if (house.getHouseLayout() != null) {
                                info.append("户型：").append(house.getHouseLayout()).append("\n");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 房屋查询失败不影响业主基本信息的返回
            }
            
            return info.toString();
        } catch (Exception e) {
            // 出现异常时返回空字符串，不中断整个流程
            return "";
        }
    }
    
    /**
     * 从知识库检索相关信息（RAG）
     */
    private String retrieveKnowledge(String question) {
        try {
            // 提取关键词进行匹配
            QueryWrapper<SmartQaKnowledge> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("status", "启用");
            
            // 尝试通过标题和描述匹配关键词
            String[] keywords = extractKeywords(question);
            
            // 改进：如果有关键词，则使用关键词进行匹配；否则返回所有启用的文档
            if (keywords != null && keywords.length > 0) {
                // 简化SQL逻辑：使用或查询，匹配任意关键词
                queryWrapper.and(w -> {
                    boolean first = true;
                    for (String keyword : keywords) {
                        if (first) {
                            w.like("title", keyword).or().like("description", keyword).or().like("tags", keyword);
                            first = false;
                        } else {
                            w.or().like("title", keyword).or().like("description", keyword).or().like("tags", keyword);
                        }
                    }
                });
            }
            // 注意：当关键词为空时，queryWrapper 只有 status 条件，会返回所有启用的文档
            
            queryWrapper.orderByDesc("view_count").last("LIMIT 5");
            
            List<SmartQaKnowledge> knowledgeList = knowledgeDao.selectList(queryWrapper);
            
            if (knowledgeList == null || knowledgeList.isEmpty()) {
                return "";
            }
            
            // 构建知识库上下文
            StringBuilder context = new StringBuilder("【社区知识库相关信息】\n");
            for (SmartQaKnowledge knowledge : knowledgeList) {
                context.append("📄 ");
                context.append(knowledge.getTitle());
                context.append(" [").append(knowledge.getCategory()).append("]\n");
                if (knowledge.getDescription() != null && !knowledge.getDescription().isEmpty()) {
                    context.append("   摘要：").append(knowledge.getDescription()).append("\n");
                }
                
                // 添加文件内容（关键步骤：下载并解析文件）
                String fileContent = downloadAndParseFile(knowledge);
                if (fileContent != null && !fileContent.isEmpty()) {
                    context.append(fileContent).append("\n");
                }
                context.append("\n");
            }
            
            return context.toString();
        } catch (Exception e) {
            System.out.println("❌ 知识库检索异常: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }
    
    /**
     * 从数据库检索相关信息
     */
    private String retrieveDatabaseInfo(String question, Long ownerId) {
        try {
            StringBuilder context = new StringBuilder();
            
            // 根据问题关键词判断查询类型
            String questionLower = question.toLowerCase();
            
            // 1. 房屋信息相关
            if (questionLower.contains("房") || questionLower.contains("户型") || 
                questionLower.contains("面积") || questionLower.contains("地址")) {
                String houseInfo = queryOwnerHouses(ownerId);
                if (!houseInfo.isEmpty()) {
                    context.append("【您的房屋信息】\n").append(houseInfo).append("\n");
                }
            }
            
            // 2. 车辆车位相关
            if (questionLower.contains("车") || questionLower.contains("停车") || questionLower.contains("车位")) {
                String vehicleInfo = queryOwnerVehicles(ownerId);
                if (!vehicleInfo.isEmpty()) {
                    context.append("【您的车辆信息】\n").append(vehicleInfo).append("\n");
                }
            }
            
            // 3. 费用相关
            if (questionLower.contains("费") || questionLower.contains("缴") || 
                questionLower.contains("账单") || questionLower.contains("欠")) {
                String feeInfo = queryOwnerFees(ownerId);
                if (!feeInfo.isEmpty()) {
                    context.append("【您的费用信息】\n").append(feeInfo).append("\n");
                }
            }
            
            // 4. 抄表信息相关
            if (questionLower.contains("水") || questionLower.contains("电") || 
                questionLower.contains("气") || questionLower.contains("表")) {
                String meterInfo = queryOwnerMeters(ownerId);
                if (!meterInfo.isEmpty()) {
                    context.append("【您的抄表信息】\n").append(meterInfo).append("\n");
                }
            }
            
            return context.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 查询业主房屋信息
     */
    private String queryOwnerHouses(Long ownerId) {
        try {
            if (ownerId == null || houseOwnerMapper == null || houseDao == null) {
                return "";
            }
            
            QueryWrapper<HouseOwner> wrapper = new QueryWrapper<>();
            wrapper.eq("owner_id", ownerId)
                   .eq("status", "正常")
                   .last("LIMIT 3");
            List<HouseOwner> houseOwners = houseOwnerMapper.selectList(wrapper);
            
            if (houseOwners == null || houseOwners.isEmpty()) {
                return "";
            }
            
            StringBuilder info = new StringBuilder();
            for (HouseOwner houseOwner : houseOwners) {
                if (houseOwner != null && houseOwner.getHouseId() != null) {
                    House house = houseDao.selectById(houseOwner.getHouseId());
                    if (house != null && house.getFullRoomNo() != null) {
                        info.append("• 房号：").append(house.getFullRoomNo());
                        if (house.getHouseLayout() != null) {
                            info.append("，户型：").append(house.getHouseLayout());
                        }
                        if (house.getBuildingArea() != null) {
                            info.append("，建筑面积：").append(house.getBuildingArea()).append("㎡");
                        }
                        info.append("\n");
                    }
                }
            }
            return info.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 查询业主车辆信息
     */
    private String queryOwnerVehicles(Long ownerId) {
        try {
            if (ownerId == null || vehicleMapper == null) {
                return "";
            }
            
            QueryWrapper<Vehicle> wrapper = new QueryWrapper<>();
            wrapper.eq("owner_id", ownerId)
                   .eq("status", "正常")
                   .last("LIMIT 3");
            List<Vehicle> vehicles = vehicleMapper.selectList(wrapper);
            
            if (vehicles == null || vehicles.isEmpty()) {
                return "";
            }
            
            StringBuilder info = new StringBuilder();
            for (Vehicle vehicle : vehicles) {
                if (vehicle != null && vehicle.getPlateNumber() != null) {
                    info.append("• 车牌：").append(vehicle.getPlateNumber());
                    if (vehicle.getBrand() != null) {
                        info.append("，品牌：").append(vehicle.getBrand());
                        if (vehicle.getModel() != null) {
                            info.append(" ").append(vehicle.getModel());
                        }
                    }
                    if (vehicle.getVehicleType() != null) {
                        info.append("，类型：").append(vehicle.getVehicleType());
                    }
                    info.append("\n");
                }
            }
            return info.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 查询业主费用信息（示例，需要实际费用表）
     */
    private String queryOwnerFees(Long ownerId) {
        try {
            QueryWrapper<HouseOwner> wrapper = new QueryWrapper<>();
            wrapper.eq("owner_id", ownerId)
                   .eq("status", "正常");
            Long houseCount = houseOwnerMapper.selectCount(wrapper);
            
            if (houseCount != null && houseCount > 0) {
                return "您名下有 " + houseCount + " 套房产。物业费缴纳请咨询物业客服。\n";
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 查询业主抄表信息
     */
    private String queryOwnerMeters(Long ownerId) {
        try {
            // 先查询业主的房屋
            QueryWrapper<HouseOwner> hoWrapper = new QueryWrapper<>();
            hoWrapper.eq("owner_id", ownerId)
                     .eq("status", "正常")
                     .last("LIMIT 1");
            HouseOwner houseOwner = houseOwnerMapper.selectOne(hoWrapper);
            
            if (houseOwner == null || houseOwner.getHouseId() == null) return "";
            
            // 查询该房屋的抄表信息
            QueryWrapper<MeterInfo> meterWrapper = new QueryWrapper<>();
            meterWrapper.eq("house_id", houseOwner.getHouseId())
                       .eq("meter_status", "正常")
                       .last("LIMIT 5");
            List<MeterInfo> meters = meterInfoMapper.selectList(meterWrapper);
            
            if (meters.isEmpty()) return "";
            
            StringBuilder info = new StringBuilder();
            for (MeterInfo meter : meters) {
                info.append("• ").append(meter.getCategoryName());
                info.append("：当前读数 ");
                if (meter.getCurrentReading() != null) {
                    info.append(meter.getCurrentReading());
                }
                if (meter.getUnit() != null) {
                    info.append(meter.getUnit());
                }
                info.append("\n");
            }
            return info.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * 提取关键词
     * 改进版：更智能地提取有意义的关键词
     */
    private String[] extractKeywords(String question) {
        try {
            if (question == null || question.trim().isEmpty()) {
                return new String[0];
            }
            
            // 常见的停用词（无实际意义的词）
            String[] stopWords = {"吗", "呢", "啊", "的", "了", "是", "在", "有", "吗", 
                    "怎么", "如何", "什么", "哪里", "为什么", "能不能", "可以", "和", "或", "及",
                    "这", "那", "与", "而", "但", "等", "等等", "、", "，", "。", "！", "？"};
            
            // 移除标点符号和停用词
            String cleaned = question;
            for (String word : stopWords) {
                cleaned = cleaned.replace(word, " ");
            }
            
            // 分词处理
            String[] words = cleaned.trim().split("\\s+|,|，|。|！|？");
            List<String> keywords = new ArrayList<>();
            for (String word : words) {
                String w = word.trim();
                // 只保留长度 >= 2 的有效词汇
                if (!w.isEmpty() && w.length() >= 2) {
                    keywords.add(w);
                }
            }
            
            // 如果关键词太多，只取前5个最有可能的关键词
            if (keywords.size() > 5) {
                return keywords.subList(0, 5).toArray(new String[0]);
            }
            
            return keywords.toArray(new String[0]);
        } catch (Exception e) {
            System.out.println("⚠️ 关键词提取异常: " + e.getMessage());
            return new String[0];
        }
    }
    
    /**
     * 判断是否有有效的本地信息
     */
    private boolean hasValidLocalInfo(String knowledgeContext, String databaseContext) {
        return (knowledgeContext != null && !knowledgeContext.isEmpty()) || 
               (databaseContext != null && !databaseContext.isEmpty());
    }
    
    /**
     * 构建系统提示词（增强版）
     */
    private String buildSystemPrompt(String ownerInfo, String knowledgeContext, 
                                     String databaseContext, boolean hasLocalInfo) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("你是一个专业的智能社区助手，负责回答业主关于社区服务、物业管理、生活便利等方面的问题。\n\n");
        
        // 添加业主信息
        if (ownerInfo != null && !ownerInfo.isEmpty()) {
            prompt.append(ownerInfo).append("\n");
        }
        
        // 添加本地信息（知识库+数据库）
        if (hasLocalInfo) {
            prompt.append("=== 社区本地信息 ===\n");
            
            if (knowledgeContext != null && !knowledgeContext.isEmpty()) {
                prompt.append(knowledgeContext).append("\n");
            }
            
            if (databaseContext != null && !databaseContext.isEmpty()) {
                prompt.append(databaseContext).append("\n");
            }
            
            prompt.append("=== 回答要求 ===\n");
            prompt.append("⭐ **【极其重要】** ⭐\n");
            prompt.append("如果上方\"社区知识库相关信息\"中包含\"详细内容\"，那么：\n");
            prompt.append("1. **必须完全基于文档内容进行回答**\n");
            prompt.append("2. **不得编造、臆断、或使用网络通用答案**\n");
            prompt.append("3. **必须从文档中提取关键信息，总结概括、分点说明**\n");
            prompt.append("4. **回答内容的表述方式可以改进，但核心内容必须来自文档**\n");
            prompt.append("5. 用\"根据社区的《\"+ 文档标题 +\"》规定，...\"的格式开头\n");
            prompt.append("6. 回答要简洁明了，重点突出，分点说明文档内容\n");
            prompt.append("7. 如果文档内容不完整或有疑问，建议业主咨询物业客服\n\n");
            
        } else {
            // 没有本地信息时的提示
            prompt.append("⚠️ **重要提示** ⚠️\n");
            prompt.append("当前社区暂无相关信息（知识库和数据库中均未找到相关内容）。\n\n");
            prompt.append("=== 回答要求 ===\n");
            prompt.append("1. **必须明确说明**：\"当前社区暂无相关信息\"\n");
            prompt.append("2. 然后说明：\"以下是网络上的常用解决方案，仅供参考\"\n");
            prompt.append("3. 再提供基于常识和专业知识的网络通用建议\n");
            prompt.append("4. 最后建议业主联系物业客服获取准确信息\n");
            prompt.append("5. 回答格式示例：\n");
            prompt.append("   \"当前社区暂无相关信息。\n");
            prompt.append("   以下是网络上的常用解决方案，仅供参考：\n");
            prompt.append("   [提供通用建议]\n");
            prompt.append("   建议您联系物业客服（电话：XXX）获取准确信息。\"\n\n");
        }
        
        prompt.append("请基于以上信息和要求，回答业主的问题。");
        
        return prompt.toString();
    }
    
    /**
     * 构建消息列表（包含历史对话）
     */
    private List<Message> buildMessages(String systemPrompt, QaRequest request) {
        List<Message> messages = new ArrayList<>();
        
        // 添加系统提示
        messages.add(new UserMessage(systemPrompt));
        
        // 添加历史对话（如果有）
        if (request.getHistory() != null && !request.getHistory().isEmpty()) {
            for (QaRequest.ChatMessage historyMsg : request.getHistory()) {
                if ("user".equals(historyMsg.getRole())) {
                    messages.add(new UserMessage(historyMsg.getContent()));
                } else if ("assistant".equals(historyMsg.getRole())) {
                    messages.add(new AssistantMessage(historyMsg.getContent()));
                }
            }
        }
        
        // 添加当前问题
        messages.add(new UserMessage(request.getQuestion()));
        
        return messages;
    }

    private String downloadAndParseFile(SmartQaKnowledge knowledge) {
        if (knowledge == null || knowledge.getFilePath() == null) {
            System.out.println("⚠️ 知识库记录为空或文件路径不存在");
            return "";
        }
        
        java.io.File f = null;
        try {
            System.out.println("\n========== 开始处理知识库文档 ==========");
            System.out.println("📚 文档标题: " + knowledge.getTitle());
            System.out.println("📂 文档分类: " + knowledge.getCategory());
            System.out.println("🔗 文件路径: " + knowledge.getFilePath());
            System.out.println("📄 文件类型: " + knowledge.getFileType());
            
            // 改进：添加文件路径的有效性检查
            if (knowledge.getFilePath().trim().isEmpty()) {
                System.out.println("❌ 文件路径为空字符串");
                return "";
            }
            
            java.io.File dir = new java.io.File("./temp/knowledge");
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                if (created) {
                    System.out.println("✅ 创建临时目录: " + dir.getAbsolutePath());
                } else {
                    System.out.println("❌ 创建临时目录失败");
                }
            }
            
            System.out.println("⬇️  正在下载文件...");
            f = downloadFile(knowledge.getFilePath(), knowledge.getId());
            if (f == null) {
                System.out.println("❌ 文件下载失败，downloadFile 返回 null");
                return "";
            }
            
            if (!f.exists()) {
                System.out.println("❌ 下载的文件不存在: " + f.getAbsolutePath());
                return "";
            }
            
            System.out.println("✅ 文件下载成功: " + f.getAbsolutePath());
            System.out.println("📊 文件大小: " + f.length() + " 字节");
            
            if (f.length() == 0) {
                System.out.println("❌ 下载的文件大小为 0 字节");
                return "";
            }
            
            String t = knowledge.getFileType();
            if (t == null || t.trim().isEmpty()) {
                System.out.println("⚠️ 文件类型为空，尝试从文件路径推断");
                String path = knowledge.getFilePath().toLowerCase();
                if (path.endsWith(".docx")) {
                    t = "docx";
                } else if (path.endsWith(".pdf")) {
                    t = "pdf";
                } else {
                    t = "txt";
                }
            }
            
            String content = "";
            System.out.println("🔄 正在解析文件内容 (" + t + ")...");
            
            if ("docx".equalsIgnoreCase(t)) {
                content = parseDocFile(f);
            } else if ("pdf".equalsIgnoreCase(t)) {
                content = parsePdfFile(f);
            } else {
                content = parseTxtFile(f);
            }
            
            if (content != null && !content.isEmpty()) {
                System.out.println("✅ 文件解析成功，内容长度: " + content.length() + " 字符");
                return "【来自文档: " + knowledge.getTitle() + " ("+knowledge.getFileType()+")】\n" + content;
            }
            
            System.out.println("⚠️ 文件内容为空，可能是文件内容本身为空或解析失败");
            return "";
        } catch (Exception e) {
            System.out.println("❌ 处理文档时出错: " + e.getMessage());
            e.printStackTrace();
            return "";
        } finally {
            // 删除临时文件
            if (f != null && f.exists()) {
                try {
                    boolean deleted = f.delete();
                    if (deleted) {
                        System.out.println("🗑️  临时文件已删除: " + f.getAbsolutePath());
                    } else {
                        System.out.println("⚠️ 删除临时文件失败: " + f.getAbsolutePath());
                    }
                } catch (Exception e) {
                    System.out.println("❌ 删除临时文件时出错: " + e.getMessage());
                }
            }
            System.out.println("========== 文档处理完成 ==========\n");
        }
    }

    private java.io.File downloadFile(String url, Long id) {
        try {
            // 改进：验证URL
            if (url == null || url.trim().isEmpty()) {
                System.out.println("  [下载] URL 为空");
                return null;
            }
            
            System.out.println("  [下载] URL: " + url);
            System.out.println("  [下载] 文档ID: " + id);
            
            java.net.URL u = new java.net.URL(url);
            java.net.URLConnection c = u.openConnection();
            c.setConnectTimeout(30000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            System.out.println("  [下载] 连接建立成功，开始传输...");
            
            int contentLength = c.getContentLength();
            System.out.println("  [下载] 内容大小: " + (contentLength > 0 ? contentLength + " 字节" : "未知"));
            
            if (contentLength == 0) {
                System.out.println("  [下载] ⚠️ 警告：内容大小为 0");
                return null;
            }
            
            java.io.File f = new java.io.File("./temp/knowledge", "knowledge_" + id + "_" + System.currentTimeMillis() + getFileExt(url));
            try (java.io.InputStream in = c.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                byte[] b = new byte[4096];
                int n;
                long totalBytes = 0;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    totalBytes += n;
                }
                System.out.println("  [下载] 传输完成，共 " + totalBytes + " 字节");
                
                if (totalBytes == 0) {
                    System.out.println("  [下载] ❌ 错误：下载的文件为空");
                    f.delete();
                    return null;
                }
                
                return f;
            }
        } catch (java.net.MalformedURLException e) {
            System.out.println("  [下载] URL 格式错误: " + e.getMessage());
            return null;
        } catch (java.net.ConnectException e) {
            System.out.println("  [下载] 连接错误: " + e.getMessage());
            return null;
        } catch (java.net.SocketTimeoutException e) {
            System.out.println("  [下载] 超时: " + e.getMessage());
            return null;
        } catch (Exception e) { 
            System.out.println("  [下载] 异常: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            return null; 
        }
    }

    private String getFileExt(String url) {
        if (!url.contains(".")) return "";
        String e = url.substring(url.lastIndexOf("."));
        return e.contains("?") ? e.substring(0, e.indexOf("?")) : e;
    }

    private String parsePdfFile(java.io.File f) {
        return "（PDF解析需要PDFBox库）";
    }

    private String parseTxtFile(java.io.File f) {
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                String line;
                int cnt = 0;
                while ((line = br.readLine()) != null && cnt < 100) {
                    sb.append(line).append("\n");
                    cnt++;
                }
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private String parseDocFile(java.io.File f) {
        try {
            System.out.println("  [解析] 检查POI库...");
            Class.forName("org.apache.poi.xwpf.usermodel.XWPFDocument");
            System.out.println("  [解析] POI库可用，开始解析DOCX文件...");
            return parseDocWithPOI(f);
        } catch (Exception e) { 
            System.out.println("  [解析] 错误: " + e.getMessage());
            return "（需要POI库支持）"; 
        }
    }

    private String parseDocWithPOI(java.io.File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = 
             new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.FileInputStream(f))) {
            
            System.out.println("  [解析] 开始提取段落内容...");
            int paragraphCount = 0;
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : doc.getParagraphs()) {
                String txt = p.getText();
                if (txt != null && !txt.isEmpty()) {
                    sb.append(txt).append("\n");
                    paragraphCount++;
                }
            }
            System.out.println("  [解析] 成功提取 " + paragraphCount + " 个段落");
            
            System.out.println("  [解析] 开始提取表格内容...");
            int tableCount = 0;
            int rowCount = 0;
            for (org.apache.poi.xwpf.usermodel.XWPFTable tbl : doc.getTables()) {
                tableCount++;
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow row : tbl.getRows()) {
                    rowCount++;
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        String ct = cell.getText();
                        if (ct != null && !ct.isEmpty()) sb.append(ct).append(" ");
                    }
                    sb.append("\n");
                }
            }
            System.out.println("  [解析] 成功提取 " + tableCount + " 个表格，共 " + rowCount + " 行");
        }
        return sb.toString();
    }
}

