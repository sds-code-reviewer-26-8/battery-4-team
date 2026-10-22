package com.example.demo.service;

import com.example.demo.entity.Member;
import com.example.demo.repository.MemberRepository;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** 회원 가입과 가입 회원 로그인 확인. 기본 계정(admin)은 AuthController 가 따로 인정한다. */
@Service
public class MemberService {

	static final String RESERVED = "admin";
	private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9]{3,20}");
	private static final int MIN_PASSWORD = 4;
	private static final int MAX_PASSWORD = 100;
	/** 새 가입자에게 적용하는 값이자, 파라미터가 비어 있는 기존 회원의 해석 값 */
	private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final int ITERATIONS = 65536;

	private final MemberRepository repository;
	private final SecureRandom random = new SecureRandom();

	public MemberService(MemberRepository repository) {
		this.repository = repository;
	}

	/** 입력을 전부 검사해 이유를 한 번에 알린다. */
	public void signup(String username, String password) {
		List<String> errors = new ArrayList<>();
		if (username == null || !USERNAME.matcher(username).matches()) {
			errors.add("아이디는 영문·숫자 3~20자로 입력해 주세요");
		} else if (RESERVED.equalsIgnoreCase(username)) {
			errors.add("사용할 수 없는 아이디입니다");
		} else if (repository.existsByUsername(username)) {
			errors.add("이미 사용 중인 아이디입니다");
		}
		if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
			errors.add("비밀번호는 " + MIN_PASSWORD + "~" + MAX_PASSWORD + "자로 입력해 주세요");
		}
		if (!errors.isEmpty()) {
			throw new BusinessException(errors);
		}
		byte[] saltBytes = new byte[16];
		random.nextBytes(saltBytes);
		String salt = Base64.getEncoder().encodeToString(saltBytes);
		try {
			repository.saveAndFlush(new Member(username, hash(password, salt, ALGORITHM, ITERATIONS), salt, ALGORITHM, ITERATIONS));
		} catch (DataIntegrityViolationException e) {
			throw new BusinessException("이미 사용 중인 아이디입니다");
		}
	}

	/** 가입 회원의 아이디·비밀번호가 맞는지 */
	public boolean authenticate(String username, String password) {
		if (username == null || password == null) {
			return false;
		}
		return repository.findByUsername(username)
				.map(m -> matches(m, password))
				.orElse(false);
	}

	private static boolean matches(Member m, String password) {
		String algorithm = m.getHashAlgorithm() != null ? m.getHashAlgorithm() : ALGORITHM;
		int iterations = m.getIterations() != null ? m.getIterations() : ITERATIONS;
		return MessageDigest.isEqual(hash(password, m.getSalt(), algorithm, iterations).getBytes(),
				m.getPasswordHash().getBytes());
	}

	private static String hash(String password, String salt, String algorithm, int iterations) {
		try {
			PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), iterations, 256);
			byte[] key = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
			return Base64.getEncoder().encodeToString(key);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
