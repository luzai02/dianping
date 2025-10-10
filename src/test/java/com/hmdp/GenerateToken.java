package com.hmdp;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * @author ghp
 * @date 2023/2/7
 * @title
 * @description
 */
@SpringBootTest
@RunWith(SpringJUnit4ClassRunner.class)
@AutoConfigureMockMvc
@Slf4j
public class GenerateToken {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private IUserService userService;

    @Resource
    private ObjectMapper mapper;

    private static final int BATCH_SIZE = 10000;
    private static final int THREAD_POOL_SIZE = 200; // 增加线程池大小以提高效率

    @Test
    // 忽视异常
    @SneakyThrows
    public void login() {
        // 修改查询限制为10000条数据
        List<String> phoneList = userService.lambdaQuery()
                .select(User::getPhone)
                .last("limit " + BATCH_SIZE)
                .list().stream().map(User::getPhone).collect(Collectors.toList());

        log.info("成功获取{}个用户手机号", phoneList.size());

        // 使用固定大小的线程池，避免创建过多线程
        ExecutorService executorService = ThreadUtil.newExecutor(THREAD_POOL_SIZE);
        // 创建List集合，存储生成的token。多线程下使用CopyOnWriteArrayList，实现读写分离，保障线程安全（ArrayList不能保障线程安全）
        List<String> tokenList = new CopyOnWriteArrayList<>();
        // 创建CountDownLatch（线程计数器）对象，用于协调线程间的同步
        CountDownLatch countDownLatch = new CountDownLatch(phoneList.size());

        // 分批处理，每批500个请求
        int batchCount = 500;
        for (int i = 0; i < phoneList.size(); i += batchCount) {
            int endIndex = Math.min(i + batchCount, phoneList.size());
            List<String> batch = phoneList.subList(i, endIndex);

            for (String phone : batch) {
                executorService.execute(() -> {
                    try {
                        // 发送获取验证码的请求，获取验证码
                        String codeJson = mockMvc.perform(MockMvcRequestBuilders
                                        .post("/user/code")
                                        .queryParam("phone", phone))
                                .andExpect(MockMvcResultMatchers.status().isOk())
                                .andReturn().getResponse().getContentAsString();
                        // 将返回的JSON字符串反序列化为Result对象
                        Result result = mapper.readerFor(Result.class).readValue(codeJson);
                        Assert.isTrue(result.getSuccess(), String.format("获取“%s”手机号的验证码失败", phone));
                        String code = result.getData().toString();

                        // 创建一个登录表单
                        // 使用建造者模式构建 登录信息对象，我这里就没有使用了，我是直接使用new（效率较低不推荐使用）
//                    LoginFormDTO formDTO = LoginFormDTO.builder().code(code).phone(phone).build();
                        LoginFormDTO formDTO = new LoginFormDTO();
                        formDTO.setCode(code);
                        formDTO.setPhone(phone);
                        // 将LoginFormDTO对象序列化为JSON
                        String json = mapper.writeValueAsString(formDTO);

                        // 发送登录请求，获取token
                        // 发送登录请求，获取返回信息（JSON字符串，其中包含token）
                        String tokenJson = mockMvc.perform(MockMvcRequestBuilders
                                        .post("/user/login").content(json).contentType(MediaType.APPLICATION_JSON))
                                .andExpect(MockMvcResultMatchers.status().isOk())
                                .andReturn().getResponse().getContentAsString();
                        // 将JSON字符串反序列化为Result对象
                        result = mapper.readerFor(Result.class).readValue(tokenJson);
                        Assert.isTrue(result.getSuccess(), String.format("获取“%s”手机号的token失败,json为“%s”", phone, json));
                        String token = result.getData().toString();
                        tokenList.add(token);
                    } catch (Exception e) {
                        log.error("处理手机号 {} 失败", phone, e);
                    } finally {
                        // 线程计数器减一
                        countDownLatch.countDown();
                    }
                });
            }

            // 每批处理完后稍作暂停，避免请求过于密集
            Thread.sleep(1000);
            log.info("已处理 {}/{} 个用户", endIndex, phoneList.size());
        }

        // 线程计数器为0时，表示所有线程执行完毕，此时唤醒主线程
        countDownLatch.await();
        // 关闭线程池
        executorService.shutdown();

        Assert.isTrue(tokenList.size() == phoneList.size(),
                String.format("生成token数量(%d)与用户数量(%d)不匹配", tokenList.size(), phoneList.size()));

        // 将生成的token写入新文件，避免覆盖原有文件
        writeToTxt(tokenList, "\\tokens_10k.txt");
        log.info("成功为{}个用户生成token并保存到文件", tokenList.size());
    }

    /**
     * 生成tokens.txt文件
     *
     * @param list
     * @param suffixPath
     * @throws Exception
     */
    private static void writeToTxt(List<String> list, String suffixPath) throws Exception {
        // 1. 创建文件
        File file = new File(System.getProperty("user.dir") + "\\src\\main\\resources" + suffixPath);
        if (!file.exists()) {
            file.createNewFile();
        }
        // 2. 输出
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        for (String content : list) {
            bw.write(content);
            bw.newLine();
        }
        bw.close();
        log.info("tokens.txt文件生成完毕！");
    }
}
