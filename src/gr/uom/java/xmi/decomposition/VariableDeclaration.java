package gr.uom.java.xmi.decomposition;

import java.util.ArrayList;
import java.util.List;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import gr.uom.java.xmi.*;

import gr.uom.java.xmi.LocationInfo.CodeElementType;
import gr.uom.java.xmi.diff.CodeRange;

public class VariableDeclaration implements LocationInfoProvider, VariableDeclarationProvider {
	private String variableName;
	private AbstractExpression initializer;
	private UMLType type;
	private boolean varargsParameter;
	private LocationInfo locationInfo;
	private boolean isParameter;
	private boolean isAttribute;
	private boolean isEnumConstant;
	private VariableScope scope;
	private boolean isFinal;
	private List<UMLAnnotation> annotations;

	public VariableDeclaration(PsiFile cu, String filePath, PsiResourceVariable fragment, VariableDeclarationContainer container) {
		this.annotations = new ArrayList<UMLAnnotation>();
		PsiModifierList modifiers = fragment.getModifierList();
		if(modifiers != null) {
			if (modifiers.hasExplicitModifier(PsiModifier.FINAL)) {
				this.isFinal = true;
			}
			for (PsiAnnotation annotation : modifiers.getAnnotations()) {
				this.annotations.add(new UMLAnnotation(cu, filePath, annotation));
			}
		}
		this.locationInfo = new LocationInfo(cu, filePath, fragment, CodeElementType.VARIABLE_DECLARATION_EXPRESSION);
		this.variableName = fragment.getName();
		this.initializer = fragment.getInitializer() != null ? new AbstractExpression(cu, filePath, fragment.getInitializer(), CodeElementType.VARIABLE_DECLARATION_INITIALIZER, container) : null;
		this.type = TypeUtils.extractType(cu, filePath, fragment);
		int startOffset = fragment.getTextRange().getStartOffset();
		PsiElement scopeNode = getScopeNode(fragment);
		int endOffset = scopeNode.getTextRange().getEndOffset();
		this.scope = new VariableScope(cu, filePath, startOffset, endOffset);
	}

	public VariableDeclaration(PsiFile cu, String filePath, PsiLocalVariable fragment, VariableDeclarationContainer container) {
		this.annotations = new ArrayList<UMLAnnotation>();
		PsiModifierList modifiers = fragment.getModifierList();
		if(modifiers != null) {
			if (modifiers.hasExplicitModifier(PsiModifier.FINAL)) {
				this.isFinal = true;
			}
			for (PsiAnnotation annotation : modifiers.getAnnotations()) {
				this.annotations.add(new UMLAnnotation(cu, filePath, annotation));
			}
		}
		PsiElement scopeNode = getScopeNode(fragment);
		if(scopeNode instanceof PsiForStatement) {
			this.locationInfo = new LocationInfo(cu, filePath, fragment, CodeElementType.VARIABLE_DECLARATION_EXPRESSION);
		}
		else {
			this.locationInfo = new LocationInfo(cu, filePath, fragment, CodeElementType.VARIABLE_DECLARATION_STATEMENT);
		}
		this.variableName = fragment.getName();
		this.initializer = fragment.getInitializer() != null ? new AbstractExpression(cu, filePath, fragment.getInitializer(), CodeElementType.VARIABLE_DECLARATION_INITIALIZER, container) : null;
		this.type = TypeUtils.extractType(cu, filePath, fragment);
		int startOffset = fragment.getTextRange().getStartOffset();
		int endOffset = scopeNode.getTextRange().getEndOffset();
		this.scope = new VariableScope(cu, filePath, startOffset, endOffset);
	}

	public VariableDeclaration(PsiFile cu, String filePath, PsiField fragment, VariableDeclarationContainer container) {
		this.annotations = new ArrayList<UMLAnnotation>();
		PsiModifierList modifiers = fragment.getModifierList();
		if(modifiers != null) {
			if (modifiers.hasExplicitModifier(PsiModifier.FINAL)) {
				this.isFinal = true;
			}
			for (PsiAnnotation annotation : modifiers.getAnnotations()) {
				this.annotations.add(new UMLAnnotation(cu, filePath, annotation));
			}
		}
		this.locationInfo = new LocationInfo(cu, filePath, fragment, CodeElementType.FIELD_DECLARATION);
		this.variableName = fragment.getName();
		this.initializer = fragment.getInitializer() != null ? new AbstractExpression(cu, filePath, fragment.getInitializer(), CodeElementType.VARIABLE_DECLARATION_INITIALIZER, container) : null;
		this.type = UMLTypePsiParser.extractTypeObject(cu, filePath, fragment.getTypeElement(), fragment.getType());
		PsiElement scopeNode = getScopeNode(fragment);
		int startOffset = scopeNode.getTextRange().getStartOffset();
		int endOffset = scopeNode.getTextRange().getEndOffset();
		this.scope = new VariableScope(cu, filePath, startOffset, endOffset);
	}

	public VariableDeclaration(PsiFile cu, String filePath, PsiParameter fragment, CodeElementType codeElementType, VariableDeclarationContainer container) {
		this.annotations = new ArrayList<UMLAnnotation>();
		PsiModifierList modifiers = fragment.getModifierList();
		if(modifiers != null) {
			if (modifiers.hasExplicitModifier(PsiModifier.FINAL)) {
				this.isFinal = true;
			}
			for (PsiAnnotation annotation : modifiers.getAnnotations()) {
				this.annotations.add(new UMLAnnotation(cu, filePath, annotation));
			}
		}
		this.locationInfo = new LocationInfo(cu, filePath, fragment, codeElementType);
		this.variableName = fragment.getName();
		this.initializer = fragment.getInitializer() != null ? new AbstractExpression(cu, filePath, fragment.getInitializer(), CodeElementType.VARIABLE_DECLARATION_INITIALIZER, container) : null;
		//handling lambda expression parameters without type
		if(fragment.getTypeElement() != null) {
			this.type = UMLTypePsiParser.extractTypeObject(cu, filePath, fragment.getTypeElement(), fragment.getType());
		}
		int startOffset = fragment.getTextRange().getStartOffset();
		PsiElement scopeNode = getScopeNode(fragment);
		int endOffset = scopeNode.getTextRange().getEndOffset();
		this.scope = new VariableScope(cu, filePath, startOffset, endOffset);
	}

	public VariableDeclaration(PsiFile cu, String filePath, PsiParameter fragment, CodeElementType codeElementType, VariableDeclarationContainer container, boolean varargs) {
		this(cu, filePath, fragment, codeElementType, container);
		this.varargsParameter = varargs;
	}

	public VariableDeclaration(PsiFile cu, String filePath, PsiEnumConstant fragment, UMLType type) {
		this.annotations = new ArrayList<UMLAnnotation>();
		PsiModifierList modifiers = fragment.getModifierList();
		if(modifiers != null) {
			if (modifiers.hasExplicitModifier(PsiModifier.FINAL)) {
				this.isFinal = true;
			}
			for (PsiAnnotation annotation : modifiers.getAnnotations()) {
				this.annotations.add(new UMLAnnotation(cu, filePath, annotation));
			}
		}
		this.isEnumConstant = true;
		this.locationInfo = new LocationInfo(cu, filePath, fragment, CodeElementType.ENUM_CONSTANT_DECLARATION);
		this.variableName = fragment.getName();
		this.initializer = null;
		this.type = type;
		PsiElement scopeNode = getScopeNode(fragment);
		int startOffset = scopeNode.getTextRange().getStartOffset();
		int endOffset = scopeNode.getTextRange().getEndOffset();
		this.scope = new VariableScope(cu, filePath, startOffset, endOffset);
	}

	public String getVariableName() {
		return variableName;
	}

	public AbstractExpression getInitializer() {
		return initializer;
	}

	public UMLType getType() {
		return type;
	}

	public VariableScope getScope() {
		return scope;
	}

	public boolean isLocalVariable() {
		return !isParameter && !isAttribute && !isEnumConstant;
	}

	public boolean isParameter() {
		return isParameter;
	}

	public void setParameter(boolean isParameter) {
		this.isParameter = isParameter;
	}

	public boolean isAttribute() {
		return isAttribute;
	}

	public void setAttribute(boolean isAttribute) {
		this.isAttribute = isAttribute;
	}

	public boolean isEnumConstant() {
		return isEnumConstant;
	}

	public boolean isVarargsParameter() {
		return varargsParameter;
	}

	public boolean isFinal() {
		return isFinal;
	}

	public List<UMLAnnotation> getAnnotations() {
		return annotations;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((scope == null) ? 0 : scope.hashCode());
		result = prime * result + ((variableName == null) ? 0 : variableName.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		VariableDeclaration other = (VariableDeclaration) obj;
		if (scope == null) {
			if (other.scope != null)
				return false;
		} else if (!scope.equals(other.scope))
			return false;
		if (variableName == null) {
			if (other.variableName != null)
				return false;
		} else if (!variableName.equals(other.variableName))
			return false;
		return true;
	}

	public boolean sameKind(VariableDeclaration other) {
		return this.isParameter == other.isParameter && this.isEnumConstant == other.isEnumConstant && this.isAttribute == other.isAttribute;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder();
        sb.append(variableName).append(" : ");
        if(varargsParameter) {
			sb.append(type.toString().substring(0, type.toString().lastIndexOf("[]")));
        	sb.append("...");
        }
		else {
			sb.append(type);
		}
        return sb.toString();
	}

	public String toQualifiedString() {
		StringBuilder sb = new StringBuilder();
        sb.append(variableName).append(" : ");
        if(varargsParameter) {
			sb.append(type.toQualifiedString().substring(0, type.toQualifiedString().lastIndexOf("[]")));
        	sb.append("...");
        }
		else {
			sb.append(type.toQualifiedString());
		}
        return sb.toString();
	}

	public LocationInfo getLocationInfo() {
		return locationInfo;
	}

	public CodeRange codeRange() {
		return locationInfo.codeRange();
	}

	private static PsiElement getScopeNode(PsiVariable variableDeclaration) {
		return PsiTreeUtil.getParentOfType(variableDeclaration,
				PsiMethod.class, PsiCodeBlock.class, PsiCatchSection.class, PsiIfStatement.class,
				PsiTryStatement.class, PsiClass.class, PsiForStatement.class, PsiWhileStatement.class,
				PsiDoWhileStatement.class, PsiSynchronizedStatement.class,
				PsiForeachStatement.class, PsiLambdaExpression.class);
	}

	public boolean equalVariableDeclarationType(VariableDeclaration other) {
		return this.locationInfo.getCodeElementType().equals(other.locationInfo.getCodeElementType());
	}

	public boolean equalType(VariableDeclaration other) {
		if(this.getType() == null && other.getType() == null) {
			return true;
		}
		else if(this.getType() != null && other.getType() != null) {
			return this.getType().equals(other.getType());
		}
		return false;
	}

	public boolean equalQualifiedType(VariableDeclaration other) {
		if(this.getType() == null && other.getType() == null) {
			return true;
		}
		else if(this.getType() != null && other.getType() != null) {
			return this.getType().equalsQualified(other.getType());
		}
		return false;
	}

	public VariableDeclaration getVariableDeclaration() {
		return this;
	}

	public void addStatementInScope(AbstractCodeFragment statement) {
		if(scope.subsumes(statement.getLocationInfo())) {
			scope.addStatement(statement);
			List<String> variables = statement.getVariables();
			if(variables.contains(variableName)) {
				scope.addStatementUsingVariable(statement);
			}
			else {
				for(String variable : variables) {
					if(variable.startsWith(variableName + ".")) {
						scope.addStatementUsingVariable(statement);
						break;
					}
				}
			}
		}
	}

	public List<AbstractCodeFragment> getStatementsInScopeUsingVariable() {
		return scope.getStatementsInScopeUsingVariable();
	}
}
