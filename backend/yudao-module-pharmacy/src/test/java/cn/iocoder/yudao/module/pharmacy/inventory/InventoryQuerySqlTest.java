package cn.iocoder.yudao.module.pharmacy.inventory;

import cn.iocoder.yudao.module.pharmacy.controller.admin.inventory.vo.InventoryReadQuery;
import cn.iocoder.yudao.module.pharmacy.dal.mysql.inventory.InventoryReadMapper;
import cn.iocoder.yudao.module.pharmacy.service.inventory.InventoryReadAccess.Scope;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the production SQL on disposable H2; this is not MySQL acceptance. */
class InventoryQuerySqlTest {
    @Test void searchesAndStatesRespectScopeAndSellability() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:stock_query_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(ds);
        try {
            db.execute("CREATE TABLE ph_warehouse(id BIGINT,tenant_id BIGINT,store_id BIGINT,wh_name VARCHAR(64),status INT,deleted INT)");
            db.execute("CREATE TABLE ph_drug(id BIGINT,tenant_id BIGINT,generic_name VARCHAR(64),trade_name VARCHAR(64),drug_code VARCHAR(64),specification VARCHAR(64),status INT,deleted INT)");
            db.execute("CREATE TABLE ph_drug_barcode(drug_id BIGINT,tenant_id BIGINT,barcode VARCHAR(64),deleted INT)");
            db.execute("CREATE TABLE ph_location(id BIGINT,tenant_id BIGINT,warehouse_id BIGINT,status INT,deleted INT)");
            db.execute("CREATE TABLE ph_inv_location_stock(batch_id BIGINT,drug_id BIGINT,tenant_id BIGINT,location_id BIGINT,qty INT,qty_frozen INT,deleted INT)");
            db.execute("CREATE TABLE ph_inv_batch(id BIGINT,warehouse_id BIGINT,tenant_id BIGINT,store_id BIGINT,drug_id BIGINT,batch_no VARCHAR(64),manufacture_date DATE,expiry_date DATE,qty_total INT,qty_avail INT,qty_frozen INT,qty_sold INT,quality_status INT,deleted INT)");
            db.update("INSERT INTO ph_warehouse VALUES(1,100,11,'A',1,0),(2,100,12,'B',1,0),(3,200,21,'C',1,0)");
            db.update("INSERT INTO ph_drug VALUES(1,100,'合成药','演示','CODE-1','10mg',1,0),(2,200,'合成药','演示','CODE-2','10mg',1,0)");
            db.update("INSERT INTO ph_drug_barcode VALUES(1,100,'690000000001',0),(2,200,'690000000002',0)");
            db.update("INSERT INTO ph_location VALUES(1,100,1,1,0),(2,100,2,1,0),(3,200,3,1,0)");
            for (int id=1;id<=6;id++) {
                int warehouse=id==5?2:id==6?3:1,tenant=id==6?200:100,store=id==5?12:id==6?21:11,drug=id==6?2:1;
                db.update("INSERT INTO ph_inv_batch VALUES(?,?,?,?,?,'B',NULL,?,20,20,?,0,?,0)",id,warehouse,tenant,store,drug,LocalDate.now().plusDays(id==2?-1:30),id==3?4:0,id==4?1:0);
                db.update("INSERT INTO ph_inv_location_stock VALUES(?,?,?,?,20,?,0)",id,drug,tenant,warehouse,id==3?4:0);
            }
            var factory = new SqlSessionFactoryBean();factory.setDataSource(ds);
            var config = new Configuration();config.setMapUnderscoreToCamelCase(true);factory.setConfiguration(config);
            factory.setMapperLocations(new ClassPathResource("mapper/inventory/InventoryReadMapper.xml"));
            var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(InventoryReadMapper.class);
            var scope=new Scope(100,11);var q=new InventoryReadQuery();
            for(String keyword:List.of("合成药","CODE-1","690000000001")) {
                q.setKeyword(keyword);assertEquals(4,mapper.countBatches(scope,q));
                assertEquals(4,mapper.selectBatches(scope,q).size());
            }
            q.setKeyword("690000000002");assertEquals(0,mapper.countBatches(scope,q));q.setKeyword(null);
            var rows=mapper.selectBatches(scope,q);
            assertEquals(20,rows.stream().filter(x->x.getId()==1).findFirst().orElseThrow().getSaleableQty());
            assertEquals(16,rows.stream().filter(x->x.getId()==3).findFirst().orElseThrow().getSaleableQty());
            assertEquals(0,rows.stream().filter(x->x.getId()==2).findFirst().orElseThrow().getSaleableQty());
            assertEquals(0,rows.stream().filter(x->x.getId()==4).findFirst().orElseThrow().getSaleableQty());
            for(String state:List.of("expired","frozen","qualityBlocked")) {
                q.setStockState(state);assertEquals(1,mapper.countBatches(scope,q));assertEquals(1,mapper.selectBatches(scope,q).size());
            }
            q.setStockState("available");assertEquals(2,mapper.countBatches(scope,q));
            q.setWarehouseId(2L);assertEquals(0,mapper.countBatches(scope,q));
            q.setWarehouseId(null);
            db.update("UPDATE ph_drug SET status=0 WHERE tenant_id=100");
            assertEquals(0,mapper.countBatches(scope,q));
            q.setStockState("disabled");assertEquals(4,mapper.countBatches(scope,q));
            assertTrue(mapper.selectBatches(scope,q).stream().allMatch(x->x.getSaleableQty()==0));
            db.update("UPDATE ph_drug SET status=1 WHERE tenant_id=100");
            db.update("UPDATE ph_location SET status=0 WHERE id=1 AND tenant_id=100");
            q.setStockState("available");assertEquals(0,mapper.countBatches(scope,q));
            q.setStockState(null);assertTrue(mapper.selectBatches(scope,q).stream().allMatch(x->x.getSaleableQty()==0));
        } finally { db.execute("SHUTDOWN"); }
    }
}
