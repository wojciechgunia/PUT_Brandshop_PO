package pl.put.brandshop.auth.services;

import io.jsonwebtoken.ExpiredJwtException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import pl.put.brandshop.auth.entity.*;
import pl.put.brandshop.auth.exceptions.UserDontExistException;
import pl.put.brandshop.auth.exceptions.UserExistingWithEmail;
import pl.put.brandshop.auth.exceptions.UserExistingWithName;
import pl.put.brandshop.auth.repository.ResetOperationsRepository;
import pl.put.brandshop.auth.repository.UserRepository;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService
{
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ResetOperationsRepository resetOperationsRepository;
    private final ResetOperationService resetOperationService;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final CookieService cookieService;
    private final EmailService emailService;
    @Value("${jwt.exp}")
    private int exp;
    @Value("${jwt.refresh.exp}")
    private int refreshExp;

    @PersistenceContext
    EntityManager entityMenager;
    private User saveUser(User user)
    {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userRepository.saveAndFlush(user);
    }

    public String generateToken(String username,int exp)
    {
        return jwtService.generateToken(username,exp);
    }

    public void validateTocken(HttpServletRequest request, HttpServletResponse response) throws ExpiredJwtException, IllegalArgumentException
    {
        String token = null;
        String refresh = null;
        if (request.getCookies() != null)
        {
            for (Cookie value : Arrays.stream(request.getCookies()).toList())
            {
                if (value.getName().equals("Authorization"))
                {
                    token = value.getValue();
                }
                else if (value.getName().equals("Refresh"))
                {
                    refresh = value.getValue();
                }
            }
        }
        else
        {
            log.info("Token can't be null, cant login");
            throw new IllegalArgumentException("Token can't be null");
        }
        try
        {
            jwtService.validateToken(token);
        }
        catch(IllegalArgumentException | ExpiredJwtException e)
        {
            jwtService.validateToken(refresh);
            Cookie refreshCookie = cookieService.generateCookie("Refresh", jwtService.refreshToken(refresh,refreshExp), refreshExp);
            Cookie cookie = cookieService.generateCookie("Authorization", jwtService.refreshToken(refresh,exp), exp);
            response.addCookie(cookie);
            response.addCookie(refreshCookie);
        }
    }

    public void register(UserRegisterDTO userRegisterDTO) throws UserExistingWithEmail,UserExistingWithName
    {
        userRepository.findUserByLogin(userRegisterDTO.getLogin()).ifPresent(value->{
            log.info("User alredy exist with this name");
            throw new UserExistingWithName("Użytkownik o tej nazwie już istnieje");
        });
        userRepository.findUserByEmail(userRegisterDTO.getEmail()).ifPresent(value->{
            log.info("User alredy exist with this email");
            throw new UserExistingWithEmail("Użytkownik o tym adresie e-mail już istnieje");
        });
        User user = new User();
        user.setLock(true);
        user.setLogin(userRegisterDTO.getLogin());
        user.setEmail(userRegisterDTO.getEmail());
        user.setPassword(userRegisterDTO.getPassword());
        user.setRole(Role.USER);
        saveUser(user);
        emailService.sendActivation(user);
    }

    public ResponseEntity<?> login(HttpServletResponse response, User authRequest)
    {
        log.info("--START Login Service");
        User user = userRepository.findUserByLoginAndLockAndEnabled(authRequest.getUsername()).orElse(null);
        if (user != null)
        {
            Authentication authenticate = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(authRequest.getUsername(), authRequest.getPassword()));
            if (authenticate.isAuthenticated())
            {
                Cookie refresh = cookieService.generateCookie("Refresh", generateToken(authRequest.getUsername(),refreshExp), refreshExp);
                Cookie cookie = cookieService.generateCookie("Authorization", generateToken(authRequest.getUsername(),exp), exp);
                response.addCookie(cookie);
                response.addCookie(refresh);
                log.info("--STOP Login Service");
                return ResponseEntity.ok(
                        UserLoginDTO
                                .builder()
                                .login(user.getUsername())
                                .email(user.getEmail())
                                .role(user.getRole())
                                .build());
            }
            else
            {
                log.info("--STOP Login Service");
                return ResponseEntity.ok(new AuthResponse(Code.A1));
            }
        }
        log.info("User dont exist");
        log.info("--STOP Login Service");
        return ResponseEntity.ok(new AuthResponse(Code.A2));
    }

    public ResponseEntity<?> loginByToken(HttpServletRequest request, HttpServletResponse response)
    {
        try
        {
            validateTocken(request,response);
            String refresh=null;
            for (Cookie value : Arrays.stream(request.getCookies()).toList())
            {
                if (value.getName().equals("Refresh"))
                {
                    refresh = value.getValue();
                }
            }
            String login = jwtService.getSubject(refresh);
            User user = (User) userRepository.findUserByLoginAndLockAndEnabled(login).orElse(null);
            if (user != null)
            {
                return ResponseEntity.ok(UserLoginDTO.builder().login(user.getUsername()).email(user.getEmail()).role(user.getRole()).build());
            }
            log.info("Cant login user dont exist");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new AuthResponse(Code.A1));
        }
        catch(ExpiredJwtException | IllegalArgumentException e)
        {
            log.info("Token expired or is null, cant login");
            return  ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new AuthResponse(Code.A3));
        }
    }

    public ResponseEntity<LoginResponse> loggedIn(HttpServletRequest request, HttpServletResponse response)
    {
        try
        {
            validateTocken(request,response);
            return  ResponseEntity.ok(new LoginResponse(true));
        }
        catch(ExpiredJwtException | IllegalArgumentException e)
        {
            return  ResponseEntity.ok(new LoginResponse(false));
        }
    }

    public void activateUser(String uid) throws UserDontExistException
    {
        User user = userRepository.findUserByUuid(uid).orElse(null);
        if(user != null)
        {
            user.setLock(false);
            user.setEnabled(true);
            userRepository.save(user);
            return;
        }
        log.info("User don't exist");
        throw new UserDontExistException("User don't exist");

    }

    public void recoveryPassword(String email) throws  UserDontExistException
    {
        User user = userRepository.findUserByEmail(email).orElse(null);
        if(user != null)
        {
            ResetOperations resetOperations = resetOperationService.initResetOperation(user);
            emailService.sendPasswordRecovery(user,resetOperations.getUid());
            return;
        }
        log.info("User don't exist");
        throw new UserDontExistException("User don't exist");
    }

    @Transactional
    public void resetPassword(ChangePasswordData changePasswordData) throws UserDontExistException
    {
        ResetOperations resetOperations = resetOperationsRepository.findByUid(changePasswordData.getUid()).orElse(null);
        if (resetOperations != null)
        {
            User user = userRepository.findUserByUuid(resetOperations.getUser().getUuid()).orElse(null);
            if(user != null)
            {
                user.setPassword(changePasswordData.getPassword());
                saveUser(user);
                resetOperationService.endOperation(resetOperations.getUid());
                return;
            }
        }
        log.info("User don't exist");
        throw new UserDontExistException("This token has expired");
    }

    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response){
        log.info("Delete all cookies");
        Cookie cookie = cookieService.removeCookie(request.getCookies(),"Authorization");
        if (cookie != null){
            response.addCookie(cookie);
        }
        cookie = cookieService.removeCookie(request.getCookies(),"Refresh");
        if (cookie != null){
            response.addCookie(cookie);
        }
        return  ResponseEntity.ok(new AuthResponse(Code.SUCCESS));
    }

    public void authorize(HttpServletRequest request) throws UserDontExistException{
        String token = null;
        String refresh = null;
        if (request.getCookies() != null){
            for (Cookie value : Arrays.stream(request.getCookies()).toList()) {
                if (value.getName().equals("Authorization")) {
                    token = value.getValue();
                } else if (value.getName().equals("Refresh")) {
                    refresh = value.getValue();
                }
            }
        }else {
            log.info("Can't login because in token is empty");
            throw new IllegalArgumentException("Token can't be null");
        }
        if (token != null && !token.isEmpty()){
            String subject = jwtService.getSubject(token);
            userRepository.findUserByLoginAndLockAndEnabledAndIsAdmin(subject).orElseThrow(()->new UserDontExistException("User not found"));
        } else if (refresh != null && !refresh.isEmpty()) {
            String subject = jwtService.getSubject(refresh);
            userRepository.findUserByLoginAndLockAndEnabledAndIsAdmin(subject).orElseThrow(()->new UserDontExistException("User not found"));
        }
    }

    public void setAsAdmin(UserRegisterDTO user) {
        userRepository.findUserByLogin(user.getLogin()).ifPresent(value->{
            value.setRole(Role.ADMIN);
            userRepository.save(value);
        });
    }

    public ResponseEntity<AuthResponse> setRole(SetRoleData setRoleData)
    {
        try
        {
            userRepository.findUserByLoginAndUuid(setRoleData.getLogin(),setRoleData.getUid()).ifPresentOrElse(value->{
                value.setRole(Role.valueOf(setRoleData.getRole()));
                userRepository.save(value);
            },()->{throw new UserDontExistException("User not found");});
            return ResponseEntity.ok(new AuthResponse(Code.SUCCESS));
        }
        catch (UserDontExistException e)
        {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new AuthResponse(Code.A2));
        }
    }

    public ResponseEntity<AuthResponse> setLock(SetLockData setLockData)
    {
        try
        {
            userRepository.findUserByLoginAndUuid(setLockData.getLogin(),setLockData.getUid()).ifPresentOrElse(value->{
                value.setLock(setLockData.isLock());
                userRepository.save(value);
            },()->{throw new UserDontExistException("User not found");});
            return ResponseEntity.ok(new AuthResponse(Code.SUCCESS));
        }
        catch (UserDontExistException e)
        {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new AuthResponse(Code.A2));
        }
    }

    public ResponseEntity<?> getUsers(int page, int limit, String name, String sort, String order)
    {
        if((name != null && !name.isEmpty()))
        {
            try
            {
                name = URLDecoder.decode(name, "UTF-8");
            } catch (UnsupportedEncodingException e)
            {
                throw new RuntimeException(e);
            }
        }
        List<UserDTO> userDTOS = new ArrayList<>();
        getAdminUsers(name, page, limit, sort, order, true).forEach(value -> {userDTOS.add(new UserDTO(value.getUuid(), value.getLogin(), value.getEmail(), value.getPassword(), value.getRole(), value.isLock(), value.isEnabled()));});
        long totalCount = userRepository.count();
        return ResponseEntity.ok().header("X-Total-Count",String.valueOf(totalCount)).body(userDTOS);
    }

    private List<User> getAdminUsers(String name, int page, int limit, String sort, String order, boolean admin)
    {
        CriteriaBuilder criteriaBuilder = entityMenager.getCriteriaBuilder();
        CriteriaQuery<User> query = criteriaBuilder.createQuery(User.class);
        Root<User> root = query.from(User.class);

        page=lowerThanOne(page);
        limit=lowerThanOne(limit);
        List<Predicate> predicates = prepareQuery(name,criteriaBuilder,root, admin);

        if(!order.isEmpty() && !sort.isEmpty())
        {
            String column = "login";
            switch (sort)
            {
                case "login":
                    column="login";
                    break;

                case "email":
                    column="email";
                    break;

                case "role":
                    column="role";
                    break;
            }
            Order orderQuery;
            if(order.equals("desc"))
            {
                orderQuery = criteriaBuilder.desc(root.get(column));
            }
            else
            {
                orderQuery = criteriaBuilder.asc(root.get(column));
            }
            query.orderBy(orderQuery);
        }

        query.where(predicates.toArray(new Predicate[0]));
        return entityMenager.createQuery(query).setFirstResult((page-1)*limit).setMaxResults(limit).getResultList();
    }

    private int lowerThanOne(int number)
    {
        if(number<1)
        {
            return 1;
        }
        return number;
    }

    private List<Predicate> prepareQuery(String name, CriteriaBuilder criteriaBuilder,Root<User> root,boolean admin)
    {
        List<Predicate> predicates = new ArrayList<>();
        if(name != null && !name.trim().equals(""))
        {
            predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("login")), "%" + name.toLowerCase() + "%"));
        }
        return predicates;
    }
}
