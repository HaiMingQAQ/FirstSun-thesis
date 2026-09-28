package cn.iocoder.yudao.module.pharmacy.service.purchase;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.InventoryReadMapper;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadAccess;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadAccess.Scope;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the production inventory read mapper's explicit tenant/store/warehouse SQL on isolated H2. */
class PurchaseInventoryReferenceSqlTest {

    private JdbcTemplate db;
    private PurchaseInventoryReferenceAccess references;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:purchase_refs_"
                + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(dataSource);
        db.execute("CREATE TABLE ph_warehouse (id BIGINT PRIMARY KEY, tenant_id BIGINT, store_id BIGINT,"
                + " wh_code VARCHAR(16), wh_name VARCHAR(64), temp_zone INT, is_default INT, status INT, deleted INT)");
        db.execute("CREATE TABLE ph_location (id BIGINT PRIMARY KEY, tenant_id BIGINT, warehouse_id BIGINT,"
                + " location_code VARCHAR(32), status INT, deleted INT)");
        db.update("INSERT INTO ph_warehouse VALUES"
                + " (111,100,11,'A','A',0,1,1,0), (112,100,12,'B','B',0,1,1,0),"
                + " (113,100,11,'C','C',0,0,1,0), (114,100,11,'D','D',0,0,0,0),"
                + " (115,100,11,'E','E',0,0,1,1), (211,200,21,'F','F',0,1,1,0)");
        db.update("INSERT INTO ph_location VALUES"
                + " (1001,100,111,'A1',1,0), (1002,100,112,'B1',1,0),"
                + " (1003,100,113,'C1',1,0), (1004,100,111,'A2',0,0),"
                + " (1005,100,111,'A3',1,1), (2001,200,211,'F1',1,0)");

        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/inventory/InventoryReadMapper.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(InventoryReadMapper.class);
        var access = mock(InventoryReadAccess.class);
        when(access.requireScope(11L)).thenReturn(new Scope(100, 11));
        references = new PurchaseInventoryReferenceAccess(access, mapper);
    }

    @AfterEach
    void cleanup() {
        db.execute("SHUTDOWN");
    }

    @Test
    void orderWarehouseRequiresCurrentTenantStoreAndEnabledRow() {
        assertDoesNotThrow(() -> references.validateOrderWarehouse(11L, null));
        assertDoesNotThrow(() -> references.validateOrderWarehouse(11L, 111L));
        for (long id : List.of(112L, 114L, 115L, 211L)) {
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ph_warehouse WHERE id=?", Integer.class, id));
            assertThrows(ServiceException.class, () -> references.validateOrderWarehouse(11L, id));
        }
    }

    @Test
    void receiptWarehouseAndLocationRequireSameTenantStoreAndWarehouse() {
        assertDoesNotThrow(() -> references.validateReceiptReferences(11L, 111L,
                Arrays.asList(1001L, 1001L, null)));
        assertThrows(ServiceException.class,
                () -> references.validateReceiptReferences(11L, null, List.of()));
        for (long warehouse : List.of(112L, 114L, 115L, 211L)) {
            assertThrows(ServiceException.class,
                    () -> references.validateReceiptReferences(11L, warehouse, List.of()));
        }
        for (long location : List.of(1002L, 1003L, 1004L, 1005L, 2001L)) {
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ph_location WHERE id=?", Integer.class, location));
            assertThrows(ServiceException.class,
                    () -> references.validateReceiptReferences(11L, 111L, List.of(location)));
        }
    }
}
