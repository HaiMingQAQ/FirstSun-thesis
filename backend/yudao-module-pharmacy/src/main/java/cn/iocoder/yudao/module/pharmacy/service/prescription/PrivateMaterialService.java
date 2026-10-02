package cn.iocoder.yudao.module.pharmacy.service.prescription;
import cn.hutool.crypto.SecureUtil;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.pharmacy.dal.dataobject.prescription.PrivateMaterialDO;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.prescription.PrivateMaterialMapper;
import cn.iocoder.yudao.module.pharmacy.service.member.AppMemberAccess;
import cn.iocoder.yudao.module.pharmacy.service.base.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
/** Separate private table: generic public infra file download cannot address these materials. */
@Service @RequiredArgsConstructor
public class PrivateMaterialService {
    private final PrivateMaterialMapper mapper;
    private final StoreService stores;
    private final PrescriptionStaffAccess staff;
    public Long upload(Long storeId, MultipartFile file) throws java.io.IOException {
        Long member = AppMemberAccess.requireMember();
        var store = stores.getStore(storeId);
        if (store == null || !Objects.equals(store.getStatus(),1)) throw new AccessDeniedException("门店不可用");
        if (file == null || file.isEmpty() || file.getSize()>5*1024*1024) throw invalidParamException("仅接受不超过 5MB 的 PNG/JPEG 图片");
        byte[] bytes = file.getBytes(); String mime = validateImage(bytes);
        String hash = SecureUtil.sha256(new ByteArrayInputStream(bytes));
        var existing=find(member,storeId,hash);if(existing!=null) return existing.getId();
        var row=new PrivateMaterialDO();row.setTenantId(TenantContextHolder.getTenantId());row.setMemberId(member);
        row.setStoreId(storeId);row.setSha256(hash);row.setMimeType(mime);row.setContent(bytes);
        try {mapper.insert(row);} catch(DuplicateKeyException ex) {return Objects.requireNonNull(find(member,storeId,hash)).getId();}
        return row.getId();
    }
    private PrivateMaterialDO find(Long member,Long store,String hash) {
        return mapper.selectOne(new LambdaQueryWrapperX<PrivateMaterialDO>().eq(PrivateMaterialDO::getMemberId,member)
                .eq(PrivateMaterialDO::getStoreId,store).eq(PrivateMaterialDO::getSha256,hash));
    }
    static String validateImage(byte[] bytes) {
        String mime;
        if(bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) mime="image/png";
        else if(bytes.length>=3 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255) mime="image/jpeg";
        else throw invalidParamException("图片格式不合法，不接受 SVG、网页或外部 URL");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalidParamException("无法读取图片");
            var reader=readers.next();try {
                reader.setInput(input);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<=0||height<=0||width>8000||height>8000||(long)width*height>20_000_000)throw invalidParamException("图片尺寸过大");
                if(reader.read(0)==null)throw invalidParamException("图片内容不完整");
            } finally {reader.dispose();}
        } catch(java.io.IOException ex) {throw invalidParamException("图片内容不完整");}
        return mime;
    }
    public void requireOwned(List<Long> ids, Long member, Long store) {
        var active = stores.getStore(store);
        if(active == null || !Objects.equals(active.getStatus(),1))throw new AccessDeniedException("门店不可用");
        if(ids==null||ids.isEmpty()||ids.size()>3||new HashSet<>(ids).size()!=ids.size())throw invalidParamException("请选择 1–3 张不同处方图片");
        for(Long id:ids){var row=mapper.selectById(id);if(row==null||!Objects.equals(row.getMemberId(),member)
                ||!Objects.equals(row.getStoreId(),store))throw new AccessDeniedException("材料不属于当前会员与门店");}
    }
    public PrivateMaterialDO getForMember(Long id) {
        Long member=AppMemberAccess.requireMember();var row=mapper.selectById(id);
        if(row==null||!Objects.equals(row.getMemberId(),member))throw new AccessDeniedException("无材料访问权限");return row;
    }
    public PrivateMaterialDO getForPharmacist(Long id) {
        var row=mapper.selectById(id);if(row==null)throw new AccessDeniedException("无材料访问权限");
        staff.requirePharmacist(row.getStoreId());return row;
    }
}
