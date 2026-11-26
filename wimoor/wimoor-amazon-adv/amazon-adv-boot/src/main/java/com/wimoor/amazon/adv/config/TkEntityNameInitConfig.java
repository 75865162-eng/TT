package com.wimoor.amazon.adv.config;

import java.util.Set;

import javax.persistence.Table;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import com.wimoor.amazon.base.BaseMapper;

import tk.mybatis.mapper.common.Mapper;
import tk.mybatis.mapper.common.MySqlMapper;
import tk.mybatis.mapper.entity.Config;
import tk.mybatis.mapper.mapperhelper.EntityHelper;
import tk.mybatis.mapper.mapperhelper.MapperHelper;
import tk.mybatis.mapper.provider.ExampleProvider;

/**
 * 广告服务混用两套 ORM：DAO 由 org.mybatis 的 MapperScan 注册（保证启动，
 * 因为部分 mapper 是 MyBatis-Plus 风格，tk 的 MapperScan 会导致启动失败），
 * 但部分通用查询仍走 tk.mybatis 的 Example/selectByExample。
 *
 * 本配置在应用启动完成后：
 *  1) 显式注册所有 JPA @Table 广告实体的表名映射（修复"无法获取实体类对应的表名"）；
 *  2) 用 tk 的 MapperHelper 处理已注册的 MappedStatement，把通用 Mapper 方法的
 *     SqlProvider 替换为携带 MapperHelper 实例的版本（修复 ExampleProvider.<init>() 无参构造异常）。
 * 不改动 MapperScan，避免破坏服务启动。
 */
@Configuration
@Order
public class TkEntityNameInitConfig implements ApplicationRunner {

    @Autowired
    private org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory;

    @Override
    public void run(ApplicationArguments args) {
        Config config = new Config();

        // 1) 注册 JPA @Table 实体表名映射
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Table.class));
        Set<BeanDefinition> defs = scanner.findCandidateComponents("com.wimoor.amazon.adv");
        for (BeanDefinition def : defs) {
            try {
                Class<?> clazz = Class.forName(def.getBeanClassName());
                EntityHelper.initEntityNameMap(clazz, config);
            } catch (Throwable ignore) {
                // 单个实体解析失败不影响其余实体，保持服务可用
            }
        }

        // 2) 用 MapperHelper 处理通用 Mapper 的 SqlProvider
        MapperHelper mapperHelper = new MapperHelper();
        mapperHelper.setConfig(config);
        mapperHelper.registerMapper(Mapper.class);
        mapperHelper.registerMapper(MySqlMapper.class);
        mapperHelper.registerMapper(BaseMapper.class);
        mapperHelper.registerMapper(ExampleProvider.class);
        mapperHelper.processConfiguration(sqlSessionFactory.getConfiguration());
    }
}
