package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 가입 회원. 권한 구분은 없고 로그인한 회원은 모두 admin 과 같다. 비밀번호는 솔트+해시로만 저장한다. */
@Entity
@Table(name = "member")
public class Member {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 20)
	private String username;

	@Column(nullable = false)
	private String passwordHash;

	@Column(nullable = false)
	private String salt;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	protected Member() {
	}

	public Member(String username, String passwordHash, String salt) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.salt = salt;
		this.createdAt = LocalDateTime.now();
	}

	public String getUsername() {
		return username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getSalt() {
		return salt;
	}
}
