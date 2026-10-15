package com.dictalab.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;
import java.nio.charset.StandardCharsets;

@Documented
@Target({ElementType.FIELD,ElementType.PARAMETER,ElementType.RECORD_COMPONENT,ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy=PasswordPolicy.Validator.class)
public @interface PasswordPolicy {
    String message() default "Mật khẩu cần ít nhất 8 ký tự, có chữ cái và chữ số, tối đa 72 byte UTF-8.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    class Validator implements ConstraintValidator<PasswordPolicy,String> {
        @Override public boolean isValid(String value,ConstraintValidatorContext context) {
            return value!=null && value.length()>=8 && value.getBytes(StandardCharsets.UTF_8).length<=72
                    && value.matches("^(?=.*[A-Za-z])(?=.*[0-9]).+$");
        }
    }
}
