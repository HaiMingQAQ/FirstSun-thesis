package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.module.infra.api.file.FileApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.pharmacy.enums.ErrorCodeConstants.*;

@Service
public class MemberAvatarService {
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    @Resource
    private FileApi fileApi;
    @Resource
    private MemberUserService memberUserService;

    public String upload(Long userId, MultipartFile file) throws Exception {
        var user = memberUserService.validateMemberUserExists(userId);
        if (!Integer.valueOf(0).equals(user.getStatus())) {
            throw exception(PHARMACY_MEMBER_USER_DISABLED);
        }
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw exception(PHARMACY_MEMBER_AVATAR_INVALID);
        }
        byte[] content;
        try (var input = file.getInputStream()) {
            content = input.readNBytes(MAX_BYTES + 1);
        }
        if (content.length == 0 || content.length > MAX_BYTES) {
            throw exception(PHARMACY_MEMBER_AVATAR_INVALID);
        }
        String format;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw exception(PHARMACY_MEMBER_AVATAR_INVALID);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) {
                    throw exception(PHARMACY_MEMBER_AVATAR_INVALID);
                }
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096
                        || (long) width * height > 16_000_000 || reader.read(0) == null) {
                    throw exception(PHARMACY_MEMBER_AVATAR_INVALID);
                }
            } finally {
                reader.dispose();
            }
        } catch (java.io.IOException | IllegalArgumentException ex) {
            throw exception(PHARMACY_MEMBER_AVATAR_INVALID);
        }
        String extension = format.equals("jpeg") ? "jpg" : "png";
        return fileApi.createFile(content, UUID.randomUUID() + "." + extension,
                "member-profile-avatar", "image/" + format);
    }
}
