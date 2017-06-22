package org.apache.james.mpt.imapmailbox.hbase;

import org.apache.james.mpt.host.JamesImapHostSystem;
import org.apache.james.mpt.imapmailbox.suite.Condstore;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;

import com.google.inject.Guice;
import com.google.inject.Injector;

@Ignore("why it was no enabled on cassandra ?")
public class HBaseCondstore extends Condstore {

    private JamesImapHostSystem system;

    @Before
    public void setUp() throws Exception {
        Injector injector = Guice.createInjector(new HBaseMailboxTestModule());
        system = injector.getInstance(JamesImapHostSystem.class);
        super.setUp();
    }
    
    @Override
    protected JamesImapHostSystem createJamesImapHostSystem() {
        return system;
    }

    @After
    public void tearDown() throws Exception {
        system.afterTest();
    }
    
}
