package com.tabbit.sensorguard.shizuku;

interface IUserService {
    // 事务码 16777114 由 Shizuku 服务端在清理 user service 时调用,必须与此一致
    void destroy() = 16777114;
    // 用户自定义退出方法
    void exit() = 1;
    // 以 shell 身份执行命令并返回输出(T2 精确归因:dumpsys sensorservice)
    String exec(String cmd) = 2;
}
