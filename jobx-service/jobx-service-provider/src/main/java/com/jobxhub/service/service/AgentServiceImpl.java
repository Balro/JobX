/**
 * Copyright (c) 2015 The JobX Project
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package com.jobxhub.service.service;

import java.util.*;

import com.google.common.collect.Lists;
import com.jobxhub.common.Constants;
import com.jobxhub.common.util.CommonUtils;
import com.jobxhub.service.api.AgentService;
import com.jobxhub.service.api.ConfigService;
import com.jobxhub.service.api.JobService;
import com.jobxhub.service.api.UserAgentService;
import com.jobxhub.service.entity.AgentEntity;
import com.jobxhub.service.vo.PageBean;
import com.jobxhub.service.vo.RestResult;
import com.jobxhub.service.job.JobXRegistry;
import com.jobxhub.service.dao.AgentDao;
import com.jobxhub.service.support.JobXTools;
import com.jobxhub.service.model.Agent;
import com.jobxhub.service.model.Job;
import com.jobxhub.service.model.User;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;

import org.apache.dubbo.config.annotation.Service;

import javax.servlet.http.HttpSession;

@Service
@Slf4j
public class AgentServiceImpl implements AgentService {

    @Autowired
    private ExecuteService executeService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private AgentDao agentDao;

    @Autowired
    private JobService jobService;

    @Autowired
    private UserAgentService userAgentService;

    @Autowired
    private JobXRegistry jobxRegistry;

    public List<Agent> getOwnerByConnType(HttpSession session) {
        List<AgentEntity> list;
        if (!JobXTools.isPermission(session)) {
            User user = JobXTools.getUser(session);
            list = agentDao.getByConnType(user.getUserId(), Constants.ConnStatus.CONNECTED.getValue());
        } else {
            list = agentDao.getByConnType(null, Constants.ConnStatus.CONNECTED.getValue());
        }
        return Lists.transform(list, Agent.transferModel);
    }

    public List<Agent> getAll() {
        List<Agent> agents = JobXTools.CACHE.get(Constants.PARAM_CACHED_AGENT_KEY, List.class);
        if (CommonUtils.isEmpty(agents)) {
            flushLocalAgent();
        }
        return JobXTools.CACHE.get(Constants.PARAM_CACHED_AGENT_KEY, List.class);
    }

    private synchronized void flushLocalAgent() {
        JobXTools.CACHE.put(
                Constants.PARAM_CACHED_AGENT_KEY,
                Lists.transform(agentDao.getAll(), Agent.transferModel)
        );
    }

    public int getCountByStatus(HttpSession session, Constants.ConnStatus status) {
        Map<String, Object> map = new HashMap<String, Object>(0);
        if (!JobXTools.isPermission(session)) {
            map.put("userId", JobXTools.getUserId(session));
        }
        map.put("status", status.getValue());
        return agentDao.getCount(map);
    }

    /**
     * 获取所属用户的agent
     *
     * @param session
     * @param pageBean
     */
    public RestResult getPageBean(HttpSession session, Agent agent, PageBean pageBean) {
        if (!JobXTools.isPermission(session)) {
            User user = JobXTools.getUser(session);
            pageBean.put("userId", user.getUserId());
        }
        pageBean.put("agentName", agent.getAgentName());
        pageBean.put("status", agent.getStatus());
        pageBean.verifyOrderBy("name", "name", "host", "port");
        List<AgentEntity> agentList = agentDao.getByPageBean(pageBean);
        if (CommonUtils.notEmpty(agentList)) {
            int count = agentDao.getCount(pageBean.getFilter());
            List<Agent> agents = Lists.transform(agentList, Agent.transferModel);
            pageBean.setResult(agents);
            pageBean.setTotalRecord(count);
        }
        return RestResult.rest(pageBean);
    }

    public Agent getAgent(Long id) {
        AgentEntity agent = agentDao.getById(id);
        if (agent != null) {
            return Agent.transferModel.apply(agent);
        }
        return null;
    }

    public void merge(Agent agent) {
        AgentEntity agentEntity = Agent.transferEntity.apply(agent);
        if (agentEntity.getAgentId() == null) {
            agentDao.save(agentEntity);
            agent.setAgentId(agentEntity.getAgentId());
        } else {
            agentDao.update(agentEntity);
        }
        flushLocalAgent();
    }

    /**
     * true can delete
     * false can't delete
     *
     * @param id
     * @return
     */
    public boolean checkDelete(Long id) {
        Agent agent = getAgent(id);
        if (agent == null) {
            return true;
        }
        List<Job> jobs = jobService.getByAgent(id);
        return jobs.isEmpty();
    }

    public void delete(Long id) {
        Agent agent = getAgent(id);
        agentDao.delete(id);
        jobxRegistry.agentUnRegister(agent);
        flushLocalAgent();
    }

    public boolean existsName(Long id, String name) {
        return agentDao.existsCount(id, "name", name) > 0;
    }

    public boolean existsHost(Long id, String host) {
        return agentDao.existsCount(id, "host", host) > 0;
    }

    public String editPassword(Long id, Boolean type, String pwd0, String pwd1, String pwd2) {
        Agent agent = this.getAgent(id);
        boolean verify;
        if (type) {//直接输入的密钥
            agent.setPassword(pwd0);
            verify = executeService.ping(agent, false).equals(Constants.ConnStatus.CONNECTED);
        } else {//密码...
            verify = DigestUtils.md5Hex(pwd0).equals(agent.getPassword());
        }
        if (verify) {
            if (pwd1.equals(pwd2)) {
                pwd1 = DigestUtils.md5Hex(pwd1);
                Boolean flag = executeService.password(agent, pwd1);
                if (flag) {
                    agent.setPassword(pwd1);
                    executeService.ping(agent, true);
                    return "true";
                } else {
                    return "false";
                }
            } else {
                return "two";
            }
        } else {
            return "one";
        }
    }

    public List<Agent> getOwnerAgents(HttpSession session) {
        PageBean<Agent> pageBean = new PageBean<Agent>(Integer.MAX_VALUE);
        pageBean.setPageNo(0);
        if (!JobXTools.isPermission(session)) {
            User userDto = JobXTools.getUser(session);
            pageBean.put("user_id", userDto.getUserId());
        }
        List<AgentEntity> agentList = agentDao.getByPageBean(pageBean);
        return Lists.transform(agentList, Agent.transferModel);
    }

    public Agent getByMacId(String machineId) {
        AgentEntity agent = agentDao.getByMacId(machineId);
        if (agent != null) {
            return Agent.transferModel.apply(agent);
        }
        return null;
    }

    public void doDisconnect(Agent agent) {
        if (CommonUtils.isEmpty(agent.getNotifyTime()) || new Date().getTime() - agent.getNotifyTime().getTime() >= configService.getSysConfig().getSpaceTime() * 60 * 1000) {
            //noticeService.notice(agent);
            //记录本次任务失败的时间
            agent.setNotifyTime(new Date());
        }
        agent.setStatus(Constants.ConnStatus.DISCONNECTED.getValue());
        merge(agent);
    }

    public void doDisconnect(String info) {
        if (CommonUtils.notEmpty(info)) {
            String macId = info.split("_")[0];
            String password = info.split("_")[1];
            Agent agent = getByMacId(macId);
            if (CommonUtils.notEmpty(agent, password) && password.equals(agent.getPassword())) {
                doDisconnect(agent);
            }
        }
    }

    /**
     * agent如果未设置host参数,则只往注册中心加入macId和password,server只能根据这个信息改过是否连接的状态
     * 如果设置了host,则会一并设置port,server端不但可以更新连接状态还可以实现agent自动注册(agent未注册的情况下)
     */
    public void doConnect(String agentInfo) {
        List<Agent> transfers = transfer(agentInfo);
        if (transfers == null) return;
        Agent registry = transfers.get(0);
        Agent agent = transfers.get(1);

        //exists in db...
        if (agent != null) {
            //agent和server密码一致则连接...
            if (registry.getPassword().equals(agent.getPassword())) {
                executeService.ping(agent, true);
            }
            return;
        }

        //not exists ,host and port null....
        if (registry.getHost() == null) {
            return;
        }

        //新的机器，需要自动注册.
        registry.setAgentName(registry.getHost());
        registry.setComment("auto registered.");
        registry.setWarning(false);
        registry.setMobile(null);
        registry.setEmail(null);
        registry.setProxyId(null);
        if (executeService.ping(registry, false).equals(Constants.ConnStatus.CONNECTED)) {
            registry.setStatus(Constants.ConnStatus.CONNECTED.getValue());
            merge(registry);
        }
    }

    private List<Agent> transfer(String registryInfo) {
        if (CommonUtils.isEmpty(registryInfo)) return null;
        String[] array = registryInfo.split("_");
        if (array.length != 3 && array.length != 5) {
            return null;
        }
        String macId = array[0];
        String password = array[1];
        String platform = array[2];
        Agent agent = new Agent();
        agent.setPlatform(Integer.parseInt(platform));
        if (array.length == 3) {
            agent.setMachineId(macId);
            agent.setPassword(password);
        } else {
            String host = array[3];
            String port = array[4];
            agent.setMachineId(macId);
            agent.setPassword(password);
            agent.setHost(host);
            agent.setPort(Integer.valueOf(port));
        }
        Agent agent1 = this.getByMacId(macId);
        return Arrays.asList(agent, agent1);
    }

    public List<Agent> getByGroup(Long groupId) {
        List<AgentEntity> list = agentDao.getByGroup(groupId);
        return Lists.transform(list, Agent.transferModel);
    }

    public void updateStatus(Agent agent) {
        if (agent != null) {
            if (agent.getAgentId() != null && agent.getStatus() != null) {
                agentDao.updateStatus(agent.getAgentId(), agent.getStatus());
            }
        }
    }
}
