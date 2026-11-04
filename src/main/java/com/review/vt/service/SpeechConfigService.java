package com.review.vt.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 语音识别配置：模型目录是**服务端级只读配置**（application.yml，环境变量 SPEECH_MODEL_DIR 可覆盖），
 * 不提供运行时修改——识别器进程级缓存，改了也要重启才生效，开放运行时改只会让人踩坑。
 */
@Service
public class SpeechConfigService {

    private final String modelDir;

    public SpeechConfigService(@Value("${myreview.speech.model-dir:E:/m_review/models/paraformer-zh}") String modelDir) {
        this.modelDir = modelDir;
    }

    public String modelDir() {
        return modelDir;
    }
}
