package io.crewscope.agentscope.coding;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Opens and integrity-checks the sole read-only Skill Bundle admitted by M4. */
public final class CodingSpecialistSkillBundle {

    public static final String SKILL_NAME = "java-spring-v1";
    public static final String SKILL_ID = SKILL_NAME + "_crewscope-java-spring-v1";
    public static final String RESOURCE = "coding-skills/java-spring-v1/SKILL.md";
    public static final String SHA_256 =
            "a5f4a5f4c9d75092df0953ef66cf8ab920d4421a0f6f64e446d5de9dc43d9170";

    private static final String RESOURCE_ROOT = "coding-skills";
    private static final String SOURCE = "crewscope-java-spring-v1";

    /** Each HarnessAgent owns one repository so closing an invocation cannot affect another. */
    ClasspathSkillRepository openRepository() {
        requireResourceHash();
        try {
            ClasspathSkillRepository repository =
                    new ClasspathSkillRepository(RESOURCE_ROOT, SOURCE);
            List<AgentSkill> skills = repository.getAllSkills();
            if (repository.isWriteable()
                    || skills.size() != 1
                    || !SKILL_NAME.equals(skills.get(0).getName())
                    || !SKILL_ID.equals(skills.get(0).getSkillId())) {
                repository.close();
                throw new IllegalStateException("Coding Skill Bundle does not match its pin");
            }
            return repository;
        } catch (IOException exception) {
            throw new IllegalStateException("Coding Skill Bundle could not be opened", exception);
        }
    }

    private static void requireResourceHash() {
        try (InputStream input = CodingSpecialistSkillBundle.class
                .getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Coding Skill Bundle resource is absent");
            }
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            if (!SHA_256.equals(actual)) {
                throw new IllegalStateException("Coding Skill Bundle integrity check failed");
            }
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Coding Skill Bundle could not be verified", exception);
        }
    }
}
