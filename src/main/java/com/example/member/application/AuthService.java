package com.example.member.application;

import com.example.member.config.TokenProvider;
import com.example.member.config.email.SmtpMailSender;
import com.example.member.dao.UserDao;
import com.example.member.domain.RoleEnum;
import com.example.member.domain.User;
import com.example.member.dto.AuthenticateResponse;
import com.example.member.dto.LoginRequest;
import com.example.member.dto.SendMailRequest;
import com.example.member.dto.SignupRequest;
import com.example.member.dto.UserinfoResponse;
import com.example.member.exception.LoginFailedException;
import com.example.member.exception.UserAlreadyExistsException;
import com.example.member.exception.UserNotAllowedException;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManagerBuilder authenticationManagerBuilder;
    private final TokenProvider tokenProvider;
    private final SmtpMailSender smtpMailSender;
    private final RedisTemplate<String, Object> redisTemplate;

    public void signup(SignupRequest request) {
        if (!confirmMail(request.getEmail(), request.getEmailCode())) {
            throw new UserNotAllowedException("인증되지 않은 사용자입니다.");
        }

        String encodedPassword = passwordEncoder.encode(request.getPassword());
        request.setPassword(encodedPassword);

        User user = User.builder()
                .email(request.getEmail())
                .password(request.getPassword())
                .build();

        userDao.save(user);

    }

    public AuthenticateResponse authenticate(LoginRequest request) {
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword());
        Authentication authentication;
        try {
            authentication = authenticationManagerBuilder.getObject().authenticate(authenticationToken);
        } catch (BadCredentialsException e) {
            throw new LoginFailedException("비밀번호가 일치하지 않습니다");
        } catch (InternalAuthenticationServiceException e) {
            throw new LoginFailedException("존재하지 않는 유저입니다");
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return AuthenticateResponse.builder()
                .token(tokenProvider.createToken(authentication))
                .refreshToken(tokenProvider.createToken(authentication))
                .build();
    }

    public String refresh(String refreshToken) {
        return tokenProvider.refresh(refreshToken);
    }

    public UserinfoResponse getUserinfo(String email) {
        User user = findUserByEmail(email);
        return UserinfoResponse.builder()
                .build();
    }

    public String createEmailAuthCode() {
        Random rnd = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(Integer.toString(rnd.nextInt(10)));
        }
        return code.toString();
    }

    public void sendEmail(SendMailRequest request) {
        if (userDao.findByEmail(request.getEmail()).isPresent()) {
            throw new UserAlreadyExistsException();
        }

        String authCode = createEmailAuthCode();

        String title = "회원 가입 인증 이메일 입니다.";
        String content =
                "<h1>환영합니다.</h1>" +
                        "<br><br>" +
                        "인증 번호는 " + authCode + "입니다." +
                        "<br>" +
                        "인증번호를 사이트에 입력해주세요.";

        smtpMailSender.sendEmail(request.getEmail(), title, content);

        redisTemplate.opsForValue().set(request.getEmail(), authCode, 5, TimeUnit.MINUTES);
    }

    public Boolean confirmMail(String email, String emailCode) {
        String authCode = (String) redisTemplate.opsForValue().get(email);

        return emailCode.equals(authCode);
    }

    private User findUserByEmail(String email) {
        Optional<User> user = userDao.findByEmail(email);
        if (user.isEmpty()) {
            throw new EntityNotFoundException();
        }
        return user.get();
    }

    private List<SimpleGrantedAuthority> toAuthorities(RoleEnum role) {
        return List.of(new SimpleGrantedAuthority(role.name()));
    }
}
