/**
 * Copyright 2009-2017 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.submitted.cache;

import java.io.Reader;
import java.lang.reflect.Field;
import java.sql.Connection;

import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.Property;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;
import org.apache.ibatis.annotations.CacheNamespaceRef;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static com.googlecode.catchexception.apis.BDDCatchException.*;
import static org.assertj.core.api.BDDAssertions.then;

// issue #524

/**
 * 1）一级缓存 Mybatis的一级缓存是指SQLSession，
 * 一级缓存的作用域是SQlSession, Mabits默认开启一级缓存。
 * 在同一个SqlSession中，执行相同的SQL查询时；
 * 第一次会去查询数据库，并写在缓存中，第二次会直接从缓存中取。
 * 当执行SQL时候两次查询中间发生了增删改的操作，则SQLSession的缓存会被清空。
 * 每次查询会先去缓存中找，如果找不到，再去数据库查询，然后把结果写到缓存中。
 * Mybatis的内部缓存使用一个HashMap，key为hashcode+statementId+sql语句。
 * Value为查询出来的结果集映射成的java对象。 SqlSession执行insert、update、delete等操作commit后会清空该SQLSession缓存。
 *
 * 2）二级缓存 二级缓存是mapper级别的，Mybatis默认是没有开启二级缓存的。
 * 第一次调用mapper下的SQL去查询用户的信息，查询到的信息会存放代该mapper对应的二级缓存区域。
 * 第二次调用namespace下的mapper映射文件中，相同的sql去查询用户信息，会去对应的二级缓存内取结果。
 */
public class CacheTest {

    private static SqlSessionFactory sqlSessionFactory;

    @Before
    public void setUp() throws Exception {
        // create a SqlSessionFactory
        Reader reader = Resources.getResourceAsReader("org/apache/ibatis/submitted/cache/mybatis-config.xml");
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        reader.close();

        // populate in-memory database
        SqlSession session = sqlSessionFactory.openSession();
        Connection conn = session.getConnection();
        reader = Resources.getResourceAsReader("org/apache/ibatis/submitted/cache/CreateDB.sql");
        ScriptRunner runner = new ScriptRunner(conn);
        runner.setLogWriter(null);
        runner.runScript(reader);
        conn.close();
        reader.close();
        session.close();
    }

    /*
     * Test Plan:
     *  1) SqlSession 1 executes "select * from A".
     *  2) SqlSession 1 closes.
     *  3) SqlSession 2 executes "delete from A where id = 1"
     *  4) SqlSession 2 executes "select * from A"
     *
     * Assert:
     *   Step 4 returns 1 row. (This case fails when caching is enabled.)
     */
    @Test
    public void testplan1() {
        SqlSession sqlSession1 = sqlSessionFactory.openSession(false);
        try {
            PersonMapper pm = sqlSession1.getMapper(PersonMapper.class);
            Assert.assertEquals(2, pm.findAll().size());
        } finally {
            sqlSession1.close();
        }

        SqlSession sqlSession2 = sqlSessionFactory.openSession(false);
        try {
            PersonMapper pm = sqlSession2.getMapper(PersonMapper.class);
            pm.delete(1);
            Assert.assertEquals(1, pm.findAll().size());
        } finally {
            sqlSession2.commit();
            sqlSession2.close();
        }
    }

    /*
     * Test Plan:
     *  1) SqlSession 1 executes "select * from A".
     *  2) SqlSession 1 closes.
     *  3) SqlSession 2 executes "delete from A where id = 1"
     *  4) SqlSession 2 executes "select * from A"
     *  5) SqlSession 2 rollback
     *  6) SqlSession 3 executes "select * from A"
     *
     * Assert:
     *   Step 6 returns 2 rows.
     */
    @Test
    public void testplan2() {
        SqlSession sqlSession1 = sqlSessionFactory.openSession(false);
        try {
            PersonMapper pm = sqlSession1.getMapper(PersonMapper.class);
            Assert.assertEquals(2, pm.findAll().size());
        } finally {
            sqlSession1.close();
        }

        SqlSession sqlSession2 = sqlSessionFactory.openSession(false);
        try {
            PersonMapper pm = sqlSession2.getMapper(PersonMapper.class);
            pm.delete(1);
        } finally {
            sqlSession2.rollback();
            sqlSession2.close();
        }

        SqlSession sqlSession3 = sqlSessionFactory.openSession(false);
        try {
            PersonMapper pm = sqlSession3.getMapper(PersonMapper.class);
            Assert.assertEquals(2, pm.findAll().size());
        } finally {
            sqlSession3.close();
        }
    }

    /*
     * Test Plan with Autocommit on:
     *  1) SqlSession 1 executes "select * from A".
     *  2) SqlSession 1 closes.
     *  3) SqlSession 2 executes "delete from A where id = 1"
     *  4) SqlSession 2 closes.
     *  5) SqlSession 2 executes "select * from A".
     *  6) SqlSession 3 closes.
     *
     * Assert:
     *   Step 6 returns 1 row.
     */
    @Test
    public void testplan3() {
        SqlSession sqlSession1 = sqlSessionFactory.openSession(true);
        try {
            PersonMapper pm = sqlSession1.getMapper(PersonMapper.class);
            Assert.assertEquals(2, pm.findAll().size());
        } finally {
            sqlSession1.close();
        }

        SqlSession sqlSession2 = sqlSessionFactory.openSession(true);
        try {
            PersonMapper pm = sqlSession2.getMapper(PersonMapper.class);
            pm.delete(1);
        } finally {
            sqlSession2.close();
        }

        SqlSession sqlSession3 = sqlSessionFactory.openSession(true);
        try {
            PersonMapper pm = sqlSession3.getMapper(PersonMapper.class);
            Assert.assertEquals(1, pm.findAll().size());
        } finally {
            sqlSession3.close();
        }
    }

    /*-
     * Test case for #405
     *
     * Test Plan with Autocommit on:
     *  1) SqlSession 1 executes "select * from A".
     *  2) SqlSession 1 closes.
     *  3) SqlSession 2 executes "insert into person (id, firstname, lastname) values (3, hello, world)"
     *  4) SqlSession 2 closes.
     *  5) SqlSession 3 executes "select * from A".
     *  6) SqlSession 3 closes.
     *
     * Assert:
     *   Step 5 returns 3 row.
     */
    @Test
    public void shouldInsertWithOptionsFlushesCache() {
        SqlSession sqlSession1 = sqlSessionFactory.openSession(true);
        try {
            PersonMapper pm = sqlSession1.getMapper(PersonMapper.class);
            Assert.assertEquals(2, pm.findAll().size());
        } finally {
            sqlSession1.close();
        }

        SqlSession sqlSession2 = sqlSessionFactory.openSession(true);
        try {
            PersonMapper pm = sqlSession2.getMapper(PersonMapper.class);
            Person p = new Person(3, "hello", "world");
            pm.createWithOptions(p);
        } finally {
            sqlSession2.close();
        }

        SqlSession sqlSession3 = sqlSessionFactory.openSession(true);
        try {
            PersonMapper pm = sqlSession3.getMapper(PersonMapper.class);
            Assert.assertEquals(3, pm.findAll().size());
        } finally {
            sqlSession3.close();
        }
    }

    /*-
     * Test Plan with Autocommit on:
     *  1) SqlSession 1 executes select to cache result
     *  2) SqlSession 1 closes.
     *  3) SqlSession 2 executes insert without flushing cache
     *  4) SqlSession 2 closes.
     *  5) SqlSession 3 executes select (flushCache = false)
     *  6) SqlSession 3 closes.
     *  7) SqlSession 4 executes select (flushCache = true)
     *  8) SqlSession 4 closes.
     *
     * Assert:
     *   Step 5 returns 2 row.
     *   Step 7 returns 3 row.
     */
    @Test
    public void shouldApplyFlushCacheOptions() {
        SqlSession sqlSession1 = sqlSessionFactory.openSession(true);
        try {
            PersonMapper pm = sqlSession1.getMapper(PersonMapper.class);
            Assert.assertEquals(2, pm.findAll().size());
        } finally {
            sqlSession1.close();
        }

        SqlSession sqlSession2 = sqlSessionFactory.openSession(true);
        try {
            PersonMapper pm = sqlSession2.getMapper(PersonMapper.class);
            Person p = new Person(3, "hello", "world");
            pm.createWithoutFlushCache(p);
        } finally {
            sqlSession2.close();
        }

        SqlSession sqlSession3 = sqlSessionFactory.openSession(true);
        try {
            PersonMapper pm = sqlSession3.getMapper(PersonMapper.class);
            Assert.assertEquals(2, pm.findAll().size());
        } finally {
            sqlSession3.close();
        }

        SqlSession sqlSession4 = sqlSessionFactory.openSession(true);
        try {
            PersonMapper pm = sqlSession4.getMapper(PersonMapper.class);
            Assert.assertEquals(3, pm.findWithFlushCache().size());
        } finally {
            sqlSession4.close();
        }
    }

    @Test
    public void shouldApplyCacheNamespaceRef() {
        {
            SqlSession sqlSession = sqlSessionFactory.openSession(true);
            try {
                PersonMapper pm = sqlSession.getMapper(PersonMapper.class);
                Assert.assertEquals(2, pm.findAll().size());
                Person p = new Person(3, "hello", "world");
                pm.createWithoutFlushCache(p);
            } finally {
                sqlSession.close();
            }
        }
        {
            SqlSession sqlSession = sqlSessionFactory.openSession(true);
            try {
                PersonMapper pm = sqlSession.getMapper(PersonMapper.class);
                Assert.assertEquals(2, pm.findAll().size());
            } finally {
                sqlSession.close();
            }
        }
        {
            SqlSession sqlSession = sqlSessionFactory.openSession(true);
            try {
                ImportantPersonMapper pm = sqlSession.getMapper(ImportantPersonMapper.class);
                Assert.assertEquals(3, pm.findWithFlushCache().size());
            } finally {
                sqlSession.close();
            }
        }
        {
            SqlSession sqlSession = sqlSessionFactory.openSession(true);
            try {
                PersonMapper pm = sqlSession.getMapper(PersonMapper.class);
                Assert.assertEquals(3, pm.findAll().size());
                Person p = new Person(4, "foo", "bar");
                pm.createWithoutFlushCache(p);
            } finally {
                sqlSession.close();
            }
        }
        {
            SqlSession sqlSession = sqlSessionFactory.openSession(true);
            try {
                SpecialPersonMapper pm = sqlSession.getMapper(SpecialPersonMapper.class);
                Assert.assertEquals(4, pm.findWithFlushCache().size());
            } finally {
                sqlSession.close();
            }
        }
        {
            SqlSession sqlSession = sqlSessionFactory.openSession(true);
            try {
                PersonMapper pm = sqlSession.getMapper(PersonMapper.class);
                Assert.assertEquals(4, pm.findAll().size());
            } finally {
                sqlSession.close();
            }
        }
    }

    @Test
    public void shouldApplyCustomCacheProperties() {
        CustomCache customCache = unwrap(sqlSessionFactory.getConfiguration().getCache(CustomCacheMapper.class.getName()));
        Assert.assertEquals("bar", customCache.getStringValue());
        Assert.assertEquals(1, customCache.getIntegerValue().intValue());
        Assert.assertEquals(2, customCache.getIntValue());
        Assert.assertEquals(3, customCache.getLongWrapperValue().longValue());
        Assert.assertEquals(4, customCache.getLongValue());
        Assert.assertEquals(5, customCache.getShortWrapperValue().shortValue());
        Assert.assertEquals(6, customCache.getShortValue());
        Assert.assertEquals((float) 7.1, customCache.getFloatWrapperValue(), 0);
        Assert.assertEquals((float) 8.1, customCache.getFloatValue(), 0);
        Assert.assertEquals(9.01, customCache.getDoubleWrapperValue(), 0);
        Assert.assertEquals(10.01, customCache.getDoubleValue(), 0);
        Assert.assertEquals((byte) 11, customCache.getByteWrapperValue().byteValue());
        Assert.assertEquals((byte) 12, customCache.getByteValue());
        Assert.assertEquals(true, customCache.getBooleanWrapperValue());
        Assert.assertEquals(true, customCache.isBooleanValue());
    }

    @Test
    public void shouldErrorUnsupportedProperties() {
        when(sqlSessionFactory.getConfiguration()).addMapper(CustomCacheUnsupportedPropertyMapper.class);
        then(caughtException()).isInstanceOf(CacheException.class)
                .hasMessage("Unsupported property type for cache: 'date' of type class java.util.Date");
    }

    @Test
    public void shouldErrorInvalidCacheNamespaceRefAttributesSpecifyBoth() {
        when(sqlSessionFactory.getConfiguration().getMapperRegistry())
                .addMapper(InvalidCacheNamespaceRefBothMapper.class);
        then(caughtException()).isInstanceOf(BuilderException.class)
                .hasMessage("Cannot use both value() and name() attribute in the @CacheNamespaceRef");
    }

    @Test
    public void shouldErrorInvalidCacheNamespaceRefAttributesIsEmpty() {
        when(sqlSessionFactory.getConfiguration().getMapperRegistry())
                .addMapper(InvalidCacheNamespaceRefEmptyMapper.class);
        then(caughtException()).isInstanceOf(BuilderException.class)
                .hasMessage("Should be specified either value() or name() attribute in the @CacheNamespaceRef");
    }

    private CustomCache unwrap(Cache cache) {
        Field field;
        try {
            field = cache.getClass().getDeclaredField("delegate");
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
        try {
            field.setAccessible(true);
            return (CustomCache) field.get(cache);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } finally {
            field.setAccessible(false);
        }
    }

    @CacheNamespace(implementation = CustomCache.class, properties = {
            @Property(name = "date", value = "2016/11/21")
    })
    private interface CustomCacheUnsupportedPropertyMapper {
    }

    @CacheNamespaceRef(value = PersonMapper.class, name = "org.apache.ibatis.submitted.cache.PersonMapper")
    private interface InvalidCacheNamespaceRefBothMapper {
    }

    @CacheNamespaceRef
    private interface InvalidCacheNamespaceRefEmptyMapper {
    }

}
