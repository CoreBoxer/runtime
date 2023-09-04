package com.alipay.sofa.serverless.arklet.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

import com.alibaba.fastjson.JSONObject;

import com.alipay.sofa.ark.common.util.BizIdentityUtils;
import com.alipay.sofa.common.utils.StringUtil;
import com.alipay.sofa.serverless.arklet.core.api.model.ResponseCode;
import com.alipay.sofa.serverless.arklet.core.command.builtin.handler.HelpHandler;
import com.alipay.sofa.serverless.arklet.core.command.builtin.handler.InstallBizHandler;
import com.alipay.sofa.serverless.arklet.core.command.builtin.handler.QueryAllBizHandler;
import com.alipay.sofa.serverless.arklet.core.command.builtin.handler.SwitchBizHandler;
import com.alipay.sofa.serverless.arklet.core.command.builtin.handler.UninstallBizHandler;
import com.alipay.sofa.serverless.arklet.core.command.coordinate.BizOpsCommandCoordinator;
import com.alipay.sofa.serverless.arklet.core.command.coordinate.CommandMutexException;
import com.alipay.sofa.serverless.arklet.core.command.executor.ExecutorServiceManager;
import com.alipay.sofa.serverless.arklet.core.command.meta.AbstractCommandHandler;
import com.alipay.sofa.serverless.arklet.core.command.meta.bizops.ArkBizMeta;
import com.alipay.sofa.serverless.arklet.core.command.meta.bizops.ArkBizOps;
import com.alipay.sofa.serverless.arklet.core.command.meta.Command;
import com.alipay.sofa.serverless.arklet.core.command.meta.InputMeta;
import com.alipay.sofa.serverless.arklet.core.command.meta.Output;
import com.alipay.sofa.serverless.arklet.core.command.record.ProcessRecord;
import com.alipay.sofa.serverless.arklet.core.command.record.ProcessRecordHolder;
import com.alipay.sofa.serverless.arklet.core.common.exception.ArkletInitException;
import com.alipay.sofa.serverless.arklet.core.common.exception.CommandValidationException;
import com.alipay.sofa.serverless.arklet.core.common.log.ArkletLogger;
import com.alipay.sofa.serverless.arklet.core.common.log.ArkletLoggerFactory;
import com.alipay.sofa.serverless.arklet.core.util.AssertUtils;
import com.google.inject.Singleton;

/**
 * @author mingmen
 * @date 2023/6/8
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Singleton
public class CommandServiceImpl implements CommandService {

    private static final ArkletLogger LOGGER = ArkletLoggerFactory.getDefaultLogger();

    private final Map<String, AbstractCommandHandler> handlerMap = new ConcurrentHashMap<>(16);

    @Override
    public void registerCommandHandler(AbstractCommandHandler handler) {
        AssertUtils.isTrue(StringUtil.isNotBlank(handler.command().getId()), "command handler id should not be blank");
        AssertUtils.isTrue(StringUtil.isNotBlank(handler.command().getDesc()), "command handler desc should not be blank");
        if (handlerMap.containsKey(handler.command().getId())) {
            throw new ArkletInitException("handler id (" + handler.command().getId() + ") duplicated");
        }
        if (isBizOpsHandler(handler)) {
            validateBizOpsHandler(handler);
        }
        handlerMap.put(handler.command().getId(), handler);
        LOGGER.info("registered command:{}", handler.command().getId());
    }

    @Override
    public void init() {
        registerBuiltInCommands();
    }

    @Override
    public void destroy() {
        handlerMap.clear();
    }

    @Override
    public Output<?> process(String cmd, Map content) throws CommandValidationException, CommandMutexException {
        AbstractCommandHandler handler = getHandler(cmd);
        InputMeta input = toJavaBean(handler.getInputClass(), content);
        handler.validate(input);

        if (isBizOpsHandler(handler)) {
            ArkBizMeta arkBizMeta = (ArkBizMeta)input;
            AssertUtils.assertNotNull(arkBizMeta, "when execute bizOpsHandler, arkBizMeta should not be null");
            boolean conflict = BizOpsCommandCoordinator.existBizProcessing(arkBizMeta.getBizName(), arkBizMeta.getBizVersion());
            if (conflict) {
                return Output.ofFailed(ResponseCode.FAILED.name() + ":" + String.format("%s %s conflict, exist unfinished command(%s) for this biz",
                    BizIdentityUtils.generateBizIdentity(arkBizMeta.getBizName(),
                        arkBizMeta.getBizVersion()), handler.command().getId(),
                    BizOpsCommandCoordinator.getCurrentProcessingCommand(arkBizMeta.getBizName(),
                        arkBizMeta.getBizVersion()).getId()));
            }
            if (arkBizMeta.isAync()) {
                String requestId = arkBizMeta.getRequestId();
                if (ProcessRecordHolder.getProcessRecord(requestId) != null) {
                    // 该requestId对应指令，已经有执行记录，不可重复执行
                    return Output.ofFailed(String.format("The request corresponding to the requestId(%s) has been executed.", requestId));
                }
                final ProcessRecord processRecord = ProcessRecordHolder.createProcessRecord(requestId);
                ThreadPoolExecutor executor = ExecutorServiceManager.getArkBizOpsExecutor();
                executor.submit(() -> {
                    try {
                        BizOpsCommandCoordinator.putBizExecution(arkBizMeta.getBizName(), arkBizMeta.getBizVersion(), handler.command());
                        processRecord.start();
                        Output output = handler.handle(input);
                        if (output.success()) {
                            processRecord.success();
                        } else {
                            processRecord.fail(output.getMessage());
                        }
                    } catch (Throwable throwable) {
                        processRecord.fail(throwable.getMessage(), throwable);
                        LOGGER.error("Error happened when handling command, requestId=" + requestId, throwable);
                    } finally {
                        processRecord.markFinishTime();
                        BizOpsCommandCoordinator.popBizExecution(arkBizMeta.getBizName(), arkBizMeta.getBizVersion());
                    }
                });
                return Output.ofSuccess(processRecord);
            } else {
                try {
                    BizOpsCommandCoordinator.putBizExecution(arkBizMeta.getBizName(), arkBizMeta.getBizVersion(), handler.command());
                    return handler.handle(input);
                } catch (Throwable e) {
                    throw e;
                } finally {
                    BizOpsCommandCoordinator.popBizExecution(arkBizMeta.getBizName(), arkBizMeta.getBizVersion());
                }
            }
        }
        return handler.handle(input);
    }

    @Override
    public boolean supported(String cmd) {
        return handlerMap.containsKey(cmd);
    }

    @Override
    public List<AbstractCommandHandler> listAllHandlers() {
        return new ArrayList<>(handlerMap.values());
    }

    private InputMeta toJavaBean(Class<InputMeta> clazz, Map map) {
        try {
            return JSONObject.parseObject(JSONObject.toJSONString(map), clazz);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public AbstractCommandHandler getHandler(Command command) {
        return getHandler(command.getId());
    }

    @Override
    public AbstractCommandHandler getHandler(String commandId) {
        AbstractCommandHandler handler = handlerMap.get(commandId);
        AssertUtils.isTrue(handler != null, commandId + " not found handler");
        return handler;
    }

    private void registerBuiltInCommands() {
        registerCommandHandler(new InstallBizHandler());
        registerCommandHandler(new HelpHandler());
        registerCommandHandler(new QueryAllBizHandler());
        registerCommandHandler(new UninstallBizHandler());
        registerCommandHandler(new SwitchBizHandler());
    }

    private boolean isBizOpsHandler(AbstractCommandHandler handler) {
        return handler instanceof ArkBizOps;
    }

    private void validateBizOpsHandler(AbstractCommandHandler handler) {
        Class inputClass = handler.getInputClass();
        if (!ArkBizMeta.class.isAssignableFrom(inputClass)) {
            throw new ArkletInitException("handler id (" + handler.command().getId() + ") is a bizOpsHandler, its input class should inherited from " + ArkBizMeta.class.getCanonicalName());
        }
    }

}
