package com.heng.service;

public interface QuestionService {
	String answer(String question) throws Exception;

	String loadTips() throws Exception;
}
