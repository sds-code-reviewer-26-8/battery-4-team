package com.example.demo.slice;

import static com.example.demo.slice.SliceSupport.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.entity.Country;
import com.example.demo.entity.Factory;
import com.example.demo.entity.Member;
import com.example.demo.entity.Order;
import com.example.demo.repository.FactoryRepository;
import com.example.demo.repository.MemberRepository;
import com.example.demo.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

/** 저장소: 새 기능이 추가한 컬럼(고객사·납기·해시 설정·공장 운영비)이 저장되고 다시 읽히는지, 컬럼 제약이 지켜지는지 본다. */
@DataJpaTest
class FeatureRepositorySliceTests {

	@Autowired
	OrderRepository orders;

	@Autowired
	MemberRepository members;

	@Autowired
	FactoryRepository factories;

	@Autowired
	EntityManager em;

	/** 저장하고 영속성 컨텍스트를 비운 뒤 다시 읽어, 실제 DB에서 읽은 값을 본다. */
	private void flushAndClear() {
		em.flush();
		em.clear();
	}

	@Test
	void orderKeepsCustomerNameAndDueDays() {
		Long id = orders.save(order("alice", 20, "삼성SDI", 12)).getId();
		flushAndClear();

		Order found = orders.findById(id).orElseThrow();
		assertThat(found.getCustomerName()).isEqualTo("삼성SDI");
		assertThat(found.getDueDays()).isEqualTo(12);
		assertThat(found.getDueOverDays()).isEqualTo(8);
	}

	/** 고객사·납기는 선택 항목이라 비워도 저장되고 null 로 읽힌다. */
	@Test
	void orderWithoutOptionalFieldsKeepsNulls() {
		Long id = orders.save(order("alice", 20, null, null)).getId();
		flushAndClear();

		Order found = orders.findById(id).orElseThrow();
		assertThat(found.getCustomerName()).isNull();
		assertThat(found.getDueDays()).isNull();
		assertThat(found.getDueOverDays()).isNull();
	}

	/** 고객사 이름 컬럼은 100자까지다. 서비스가 놓쳐도 DB 가 한 번 더 막는다. */
	@Test
	void customerNameColumnAllows100CharsAndRejects101() {
		orders.saveAndFlush(order("alice", 20, "가".repeat(100), null));
		assertThatThrownBy(() -> orders.saveAndFlush(order("alice", 20, "가".repeat(101), null)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void memberKeepsHashParametersAndLegacyNulls() {
		members.save(new Member("newbie", "hash", "salt", "PBKDF2WithHmacSHA256", 65536));
		members.save(new Member("legacy", "hash", "salt"));
		flushAndClear();

		Member newbie = members.findByUsername("newbie").orElseThrow();
		assertThat(newbie.getHashAlgorithm()).isEqualTo("PBKDF2WithHmacSHA256");
		assertThat(newbie.getIterations()).isEqualTo(65536);
		Member legacy = members.findByUsername("legacy").orElseThrow();
		assertThat(legacy.getHashAlgorithm()).isNull();
		assertThat(legacy.getIterations()).isNull();
	}

	/** 같은 아이디로 두 번 가입할 수 없다(고유 제약). */
	@Test
	void duplicateUsernameIsRejectedByUniqueConstraint() {
		members.saveAndFlush(new Member("dup", "hash", "salt"));

		assertThat(members.existsByUsername("dup")).isTrue();
		assertThat(members.existsByUsername("other")).isFalse();
		assertThatThrownBy(() -> members.saveAndFlush(new Member("dup", "hash2", "salt2")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void factoryKeepsDailyOperatingCostAndLegacyNull() {
		factories.save(new Factory(Country.CHINA, 400, 4000, 700, 1_000_000_000L));
		factories.save(new Factory(Country.JAPAN, 800, 2000, 900));
		flushAndClear();

		assertThat(factories.findById(Country.CHINA).orElseThrow().getDailyOperatingCostWon())
				.isEqualTo(1_000_000_000L);
		assertThat(factories.findById(Country.JAPAN).orElseThrow().getDailyOperatingCostWon()).isNull();
	}
}
