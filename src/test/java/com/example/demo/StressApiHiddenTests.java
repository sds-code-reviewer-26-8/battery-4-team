package com.example.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** stress 프로필이 없으면 부하 시험용 API 는 열리지 않고, 로그인 규칙(INTENT 원칙 14)이 그대로 적용된다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:stress-hidden;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class StressApiHiddenTests {

	@Autowired
	MockMvc mvc;

	@Test
	void stressApiIsNotOpenWithoutProfile() throws Exception {
		mvc.perform(get("/api/stress/health")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/stress/orders")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/stress/simulate")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/stress/orders")).andExpect(status().isUnauthorized());
	}
}
