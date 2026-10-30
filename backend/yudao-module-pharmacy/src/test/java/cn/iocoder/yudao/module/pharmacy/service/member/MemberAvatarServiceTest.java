package cn.iocoder.yudao.module.pharmacy.service.member;

import cn.iocoder.yudao.module.infra.api.file.FileApi;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.member.MemberUserDO;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemberAvatarServiceTest {
    final FileApi files=mock(FileApi.class);
    final MemberUserService users=mock(MemberUserService.class);
    final MemberAvatarService service=new MemberAvatarService();
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"fileApi",files);ReflectionTestUtils.setField(service,"memberUserService",users);
        var u=new MemberUserDO();u.setStatus(0);when(users.validateMemberUserExists(9L)).thenReturn(u);
        when(files.createFile(any(byte[].class),anyString(),anyString(),anyString())).thenReturn("http://unit/avatar.png");
    }
    byte[] image(String format,int width,int height)throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),format,out);return out.toByteArray();
    }
    @Test void realContentDeterminesTypeAndEachUploadGetsUniqueServerName()throws Exception {
        var f=new MockMultipartFile("file","same.html","text/html",image("png",2,2));service.upload(9L,f);service.upload(9L,f);
        var names=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(files,times(2)).createFile(any(byte[].class),names.capture(),eq("member-profile-avatar"),eq("image/png"));
        assertNotEquals(names.getAllValues().get(0),names.getAllValues().get(1));
        assertTrue(names.getValue().matches("[a-f0-9-]{36}\\.png"));
    }
    @Test void jpegIsAccepted()throws Exception {
        service.upload(9L,new MockMultipartFile("file","a.png","image/png",image("jpeg",2,2)));
        verify(files).createFile(any(byte[].class),endsWith(".jpg"),eq("member-profile-avatar"),eq("image/jpeg"));
    }
    @Test void spoofedEmptyLargeAndOversizeDimensionsNeverReachStorage()throws Exception {
        for(byte[] bytes:new byte[][]{new byte[0],"<html>fake</html>".getBytes(),image("gif",2,2),new byte[5*1024*1024+1],image("png",4097,1)}) {
            assertThrows(RuntimeException.class,()->service.upload(9L,new MockMultipartFile("file","a.png","image/png",bytes)));
        }
        verifyNoInteractions(files);
    }
}
