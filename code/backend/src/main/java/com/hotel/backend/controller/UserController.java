package com.hotel.backend.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
/**
 * REST Controller cho User.
 *
 * Base URL: /api/user
 *  
 * 
 */

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.hotel.backend.dto.request.UserCreationRequest;
import com.hotel.backend.dto.request.UserCreationWithTypeRequest;
import com.hotel.backend.dto.request.UserPasswordRequest;
import com.hotel.backend.dto.request.UserUpdateRequest;
import com.hotel.backend.dto.request.AdminResetPasswordRequest;
import com.hotel.backend.dto.response.UserPageResponse;
import com.hotel.backend.dto.response.UserResponse;
import com.hotel.backend.service.UserService;
import com.hotel.backend.entity.User;
import com.hotel.backend.constant.UserType;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j(topic = "USER-CONTROLLER")
public class UserController {

    private final UserService userService;

    @Operation(summary = "Create User", description = "API add new user to database")
    @PostMapping("")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Object> CreateUser(@RequestBody @Valid UserCreationWithTypeRequest request){
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("status", HttpStatus.CREATED.value());

        result.put("message","User created successfully");

        result.put("data",userService.createUserWithType(request));
        
        return new ResponseEntity<>(result,HttpStatus.CREATED);
    }



    @Operation(summary = "Update User", description = "API update user to database")
    @PutMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
    public ResponseEntity<Map<String, Object>> updateUser(
            @PathVariable Long userId,
            @Valid @RequestBody UserUpdateRequest request,
            @AuthenticationPrincipal User currentUser) {

        // Người dùng chỉ được sửa thông tin hồ sơ của chính mình, không được
        // nâng quyền bằng cách gửi type=ADMIN/STAFF trong request. ADMIN vẫn có
        // thể thay đổi role khi quản lý người dùng từ dashboard.
        if (currentUser == null) {
            throw new AccessDeniedException("Bạn chưa đăng nhập");
        }
        if (!UserType.ADMIN.equals(currentUser.getType())) {
            request.setType(currentUser.getType());
        }

        userService.update(request,userId);
            
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", HttpStatus.OK.value());
        result.put("message", "User updated successfully");
            
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Change Password", description = "API change password user")
    @PatchMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public Map<String,Object> changePassword(
            @RequestBody @Valid UserPasswordRequest request,
            @AuthenticationPrincipal User currentUser){

        if (currentUser == null || !currentUser.getId().equals(request.getId())) {
            throw new AccessDeniedException("Bạn chỉ có thể đổi mật khẩu của chính mình");
        }

        userService.changePassword(request);
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("status", HttpStatus.NO_CONTENT.value());
        result.put("message","Password updated successfully");
        result.put("data","");
        return result;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin reset password", description = "Admin sets a new password for a user")
    @PatchMapping("/{userId}/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(
            @PathVariable Long userId,
            @Valid @RequestBody AdminResetPasswordRequest request) {
        userService.resetPasswordByAdmin(userId, request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", HttpStatus.OK.value());
        result.put("message", "Password reset successfully");
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Delete User", description = "API delete user to database")
    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Object> deleteUser(@PathVariable @Min(value = 1 , message = "user id must be equal or greater than 1") Long userId){

        userService.delete(userId);
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("status", HttpStatus.RESET_CONTENT.value());
        result.put("message","User deleted successfully");
        result.put("data","");
        return result;
    }
    
    @Operation(summary = "Get current user", description = "API retrieve the authenticated user's profile")
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> getCurrentUser(@AuthenticationPrincipal User currentUser) {
        if (currentUser == null) {
            throw new AccessDeniedException("Bạn chưa đăng nhập");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", HttpStatus.OK.value());
        result.put("message", "Get current user successfully");
        result.put("data", userService.findById(currentUser.getId()));
        return result;
    }

    @Operation(summary = "Get detail User", description = "API retrieve user detail by id")
    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF') or #userId == authentication.principal.id")
    public Map<String,Object> getUserDetail(@PathVariable @Min(value = 1 , message = "user id must be equal or greater than 1") Long userId){

        UserResponse userDetail=userService.findById(userId);
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("status", HttpStatus.OK.value());
        result.put("message","Get user by id successfully");
        result.put("data",userDetail);
        return result;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @Operation(summary = "Get list User", description = "API get list")
    @GetMapping("/list")
    public Map<String,Object> getList(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size){

        UserPageResponse pageResponse= userService.findAll(keyword,sort,page,size);
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("status", HttpStatus.OK.value());
        result.put("message","Get user by id successfully");
        result.put("data",pageResponse);
        return result;
    }

    

}
