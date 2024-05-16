package roomescape.member.domain;

import io.micrometer.common.util.StringUtils;
import roomescape.global.exception.error.ErrorType;
import roomescape.global.exception.model.ValidateException;

public record Password(String value) {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 16;

    public Password(final String value) {
        this.value = value;

        validateBlank();
        validateLength();
    }

    private void validateBlank() {
        if (StringUtils.isBlank(value)) {
            throw new ValidateException(ErrorType.REQUEST_DATA_BLANK,
                    String.format("회원(Member)의 비밀번호(Password)에 유효하지 않은 값(null OR 공백)이 입력되었습니다."));
        }
    }

    private void validateLength() {
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new ValidateException(ErrorType.INVALID_REQUEST_DATA,
                    String.format("회원(Member)의 비밀번호(Password)는 %d자 이상 %d자 이하여야 합니다.", MIN_LENGTH, MAX_LENGTH));
        }
    }
}

