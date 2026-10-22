package cn.iocoder.yudao.module.infra.controller.app.file;

import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FileCreateReqVO;
import cn.iocoder.yudao.module.infra.controller.app.file.vo.AppFileUploadReqVO;
import cn.iocoder.yudao.module.infra.service.file.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppFileAvatarNamespaceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "member-profile-avatar", "member-profile-avatar/sub", "MEMBER-PROFILE-AVATAR/a.png",
            "member-profile-avatar. ", "Member-Profile-Avatar.../a.png", "member-profile-avatar /sub",
            "member-profile-avatar\\sub"})
    void allThreeEntriesRejectReservedAliasesBeforeStorageOrFileRead(String value) throws Exception {
        var files = mock(FileService.class);
        var controller = new AppFileController();
        ReflectionTestUtils.setField(controller, "fileService", files);
        var file = spy(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}));
        var upload = new AppFileUploadReqVO(); upload.setFile(file); upload.setDirectory(value);
        assertThrows(RuntimeException.class, () -> controller.uploadFile(upload));
        assertThrows(RuntimeException.class, () -> controller.getFilePresignedUrl("a.png", value));
        var create = new FileCreateReqVO();create.setName("a.png");create.setPath(value);
        assertThrows(RuntimeException.class, () -> controller.createFile(create));
        verify(file, never()).getInputStream(); verifyNoInteractions(files);
    }

    @Test
    void oldAndNeighboringDirectoriesRemainUsable() throws Exception {
        var files = mock(FileService.class);var controller = new AppFileController();
        ReflectionTestUtils.setField(controller, "fileService", files);
        var upload = new AppFileUploadReqVO();
        upload.setFile(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}));
        upload.setDirectory("member-avatar");controller.uploadFile(upload);
        controller.getFilePresignedUrl("a.png", "member-profile-avatar-other");
        var create = new FileCreateReqVO();create.setName("a.png");create.setPath("member-profile-avatar2/a.png");
        controller.createFile(create);
        verify(files).createFile(any(byte[].class),eq("a.png"),eq("member-avatar"),eq("image/png"));
        verify(files).presignPutUrl("a.png","member-profile-avatar-other");verify(files).createFile(create);
    }
    @Test
    void genericWritesCannotEnterDedicatedAvatarNamespace() throws Exception {
        var files = mock(FileService.class);
        var controller = new AppFileController();
        ReflectionTestUtils.setField(controller, "fileService", files);
        var upload = new AppFileUploadReqVO();
        upload.setFile(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}));
        upload.setDirectory("member-profile-avatar/20261023");
        assertThrows(RuntimeException.class, () -> controller.uploadFile(upload));
        assertThrows(RuntimeException.class, () -> controller.getFilePresignedUrl("a.png", "Member-Profile-Avatar. /date"));
        var create = new FileCreateReqVO();
        create.setPath("member-profile-avatar/a.png");
        create.setName("a.png");
        assertThrows(RuntimeException.class, () -> controller.createFile(create));
        verifyNoInteractions(files);
    }
}
