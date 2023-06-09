package com.heng.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.heng.service.QuestionService;

@RestController
@RequestMapping("/heng/question")
public class QuestionController {

    @Autowired
    QuestionService questionService;

    @RequestMapping("/loadTips")
    public String loadTips() {
        String tips = null;
        try {
            tips = questionService.loadTips();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return tips;
    }

    @RequestMapping("/query")
    public String query(@RequestParam(value = "question") String question) {
        String answer = "系统繁忙，请稍后重试...";
        try {
            answer = questionService.answer(question);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return answer;
    }

}
