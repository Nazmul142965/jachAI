package com.jachai.ai.service;

public interface AiService {
    String generate(AiUseCase useCase, String systemInstruction, String userInput);
}
