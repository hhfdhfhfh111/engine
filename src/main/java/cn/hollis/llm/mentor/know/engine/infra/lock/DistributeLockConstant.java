package cn.hollis.llm.mentor.know.engine.infra.lock;

/**
 * 分布式锁常量
 *
 * @author hollis
 */
public class DistributeLockConstant {

    public static final String NONE_KEY = "NONE";

    public static final String DEFAULT_OWNER = "DEFAULT";

    public static final int DEFAULT_EXPIRE_TIME = -1;

    public static final int DEFAULT_WAIT_TIME = Integer.MAX_VALUE;
}
