package com.portfolio.compliance.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.compliance.ComplianceDocAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = ComplianceDocAgentApplication.class)
@AutoConfigureMockMvc
class RequestErrorContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void malformedJsonReturnsActionable400InsteadOf500() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .with(httpBasic("reviewer@demo.local", "demo-change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数或请求体格式不正确"));
    }

    @Test
    void unsupportedMethodReturns405InsteadOf500() throws Exception {
        mockMvc.perform(put("/api/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
    }
}
