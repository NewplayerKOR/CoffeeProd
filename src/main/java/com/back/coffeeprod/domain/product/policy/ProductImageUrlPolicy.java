package com.back.coffeeprod.domain.product.policy;

import com.back.coffeeprod.global.exception.CustomException;
import com.back.coffeeprod.global.exception.ErrorCode;

import java.net.URI;
import java.util.regex.Pattern;

public final class ProductImageUrlPolicy {

    public static final String ALLOWED_HOST = "assets-coffeeprod.ttagyulab.com";
    public static final String PATH_PREFIX = "/products/catalog/";

    private static final Pattern ASSET_PATH_PATTERN = Pattern.compile(
            "^" + Pattern.quote(PATH_PREFIX) + "[a-z0-9]+(?:-[a-z0-9]+)*-v[1-9][0-9]*[.]webp$"
    );

    private ProductImageUrlPolicy() {
    }

    // 공백 값을 null로 정규화하고 승인된 R2 URL만 허용함
    public static String normalize(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }

        String normalized = imageUrl.trim();
        URI uri = parse(normalized);

        if (!isAllowed(uri)) {
            throw new CustomException(ErrorCode.INVALID_PRODUCT_IMAGE_URL);
        }

        return normalized;
    }

    private static URI parse(String imageUrl) {
        try {
            return URI.create(imageUrl);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(ErrorCode.INVALID_PRODUCT_IMAGE_URL);
        }
    }

    private static boolean isAllowed(URI uri) {
        return uri.isAbsolute()
                && "https".equalsIgnoreCase(uri.getScheme())
                && ALLOWED_HOST.equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1
                && uri.getUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && uri.getRawPath() != null
                && ASSET_PATH_PATTERN.matcher(uri.getRawPath()).matches();
    }
}
