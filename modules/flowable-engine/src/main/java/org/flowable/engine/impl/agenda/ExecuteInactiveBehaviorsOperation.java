/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flowable.engine.impl.agenda;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.Process;
import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.impl.delegate.InactiveActivityBehavior;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.impl.util.ProcessDefinitionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Operation that usually gets scheduled as last operation of handling a {@link Command}.
 * 
 * Executes 'background' behaviours of executions that currently are in an activity that implements the {@link InactiveActivityBehavior} interface.
 * 
 * @author Joram Barrez
 */
public class ExecuteInactiveBehaviorsOperation extends AbstractOperation {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecuteInactiveBehaviorsOperation.class);

    protected Collection<ExecutionEntity> involvedExecutions;

    public ExecuteInactiveBehaviorsOperation(CommandContext commandContext, Collection<ExecutionEntity> executions) {
        super(commandContext, null);
        this.involvedExecutions = executions;
    }

    @Override
    public void run() {

        /*
         * Algorithm: for each execution that is involved in this command context,
         * 
         * 1) Get its process definition
         * 2) Verify if its process definitions has any InactiveActivityBehavior behaviours.
         * 3) If so, verify if there are any executions inactive in those activities
         * 4) Execute the inactivated behavior
         */

        Set<String> handledProcessInstanceIds = null;

        for (ExecutionEntity executionEntity : involvedExecutions) {

            String processInstanceId = executionEntity.getProcessInstanceId();
            if (handledProcessInstanceIds == null || !handledProcessInstanceIds.contains(processInstanceId)) {

                Process process = ProcessDefinitionUtil.getProcess(executionEntity.getProcessDefinitionId());
                Collection<String> flowNodeIdsWithInactivatedBehavior = new ArrayList<>();
                for (FlowNode flowNode : process.findFlowElementsOfType(FlowNode.class)) {
                    if (flowNode.getBehavior() instanceof InactiveActivityBehavior) {
                        flowNodeIdsWithInactivatedBehavior.add(flowNode.getId());
                    }
                }

                if (flowNodeIdsWithInactivatedBehavior.size() > 0) {
                    Collection<ExecutionEntity> inactiveExecutions = CommandContextUtil.getExecutionEntityManager(commandContext)
                        .findInactiveExecutionsByProcessInstanceId(processInstanceId);
                    for (ExecutionEntity inactiveExecution : inactiveExecutions) {
                        if (!inactiveExecution.isActive()
                            && flowNodeIdsWithInactivatedBehavior.contains(inactiveExecution.getActivityId())
                            && !inactiveExecution.isDeleted()) {

                            FlowNode flowNode = (FlowNode) process.getFlowElement(inactiveExecution.getActivityId(), true);
                            InactiveActivityBehavior inactiveActivityBehavior = ((InactiveActivityBehavior) flowNode.getBehavior());
                            LOGGER.debug("Found InactiveActivityBehavior instance of class {} that can be executed on activity '{}'", inactiveActivityBehavior.getClass(), flowNode.getId());
                            inactiveActivityBehavior.executeInactive(inactiveExecution);
                        }
                    }

                    if (handledProcessInstanceIds == null) {
                        handledProcessInstanceIds = new HashSet<>();
                    }
                    handledProcessInstanceIds.add(processInstanceId);

                }

            }

        }
    }

}
