package cn.iocoder.yudao.module.pharmacy.service.prescription;

import cn.iocoder.yudao.module.pharmacy.dal.mysql.prescription.PrivateMaterialMapper;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateMaterialServiceTest {
    @Test void validatesActualImageContentAndRejectsSvgHtmlAndTruncatedImages() throws Exception {
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);
        assertEquals("image/png",PrivateMaterialService.validateImage(output.toByteArray()));
        for(byte[] invalid:java.util.List.of("<svg/>".getBytes(),"<html/>".getBytes(),"https://file.example/a.png".getBytes(),java.util.Arrays.copyOf(output.toByteArray(),8)))
            assertThrows(RuntimeException.class,()->PrivateMaterialService.validateImage(invalid));
    }
    @Test void guestCannotReadOrUploadPrivateMaterials() {
        var mapper=mock(PrivateMaterialMapper.class);var stores=mock(StoreService.class);var staff=mock(PrescriptionStaffAccess.class);
        var service=new PrivateMaterialService(mapper,stores,staff);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.getForMember(1L));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.upload(1L,null));
        verifyNoInteractions(mapper,stores,staff);
    }
}
