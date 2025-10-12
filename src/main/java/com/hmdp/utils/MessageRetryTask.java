package com.hmdp.utils;

import com.hmdp.entity.OrderMessage;
import com.hmdp.service.IOrderMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@Component
public class MessageRetryTask {

    @Resource
    private IOrderMessageService orderMessageService;

    @Scheduled(fixedRate = 60000)
    public void retryFailedMessages(){
        try{
            List<OrderMessage> retryMessages = orderMessageService.getRetryMessages();

            if(retryMessages != null && !retryMessages.isEmpty()){
                log.info("开始重试处理失败的消息，数量：{}", retryMessages.size());
                for (OrderMessage message : retryMessages) {
                    if(message.getRetryCount() >= message.getRetryCount()){
                        // 达到最大重试次数，标记为死信
                        orderMessageService.markAsDeadLetter(message);
                    }else{
                        // 继续重试
                        orderMessageService.retryMessage(message);
                    }
                }
            }
        }catch (Exception e){
            log.error("重试处理失败的消息异常", e);
        }
    }


}
